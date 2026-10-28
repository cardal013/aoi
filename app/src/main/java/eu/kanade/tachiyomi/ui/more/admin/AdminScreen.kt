package eu.kanade.tachiyomi.ui.more.admin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Screen
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Admin: Savearth, Gits, Refeições, Trello e Definições, numa página web (assets/admin/index.html, a mesma do site).
 * Só aparece no More para contas admin. Os dados sincronizam com a cloud através do [AdminViewModel].
 */
class AdminScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<AdminViewModel>()
        var webView by remember { mutableStateOf<WebView?>(null) }

        // <input type="file"> da página (anexos do Trello): a resposta tem de chegar sempre, mesmo vazia
        var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            fileCallback = null
        }

        // Voltar fecha primeiro o que estiver aberto na página (modal, menu, outra aba)
        BackHandler {
            val view = webView
            if (view == null) {
                navigator.pop()
            } else {
                view.evaluateJavascript("window.aoiBack ? aoiBack() : false") { handled ->
                    if (handled != "true") navigator.pop()
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(BACKGROUND)
                .windowInsetsPadding(WindowInsets.systemBars),
            factory = { context ->
                createWebView(
                    context = context,
                    viewModel = viewModel,
                    onClose = { navigator.pop() },
                    onPickFile = { callback ->
                        fileCallback?.onReceiveValue(null)
                        fileCallback = callback
                        filePicker.launch("*/*")
                    },
                ).also { webView = it }
            },
        )

        // A página é escura: ícones claros na barra de estado enquanto o Admin está aberto
        val view = LocalView.current
        DisposableEffect(Unit) {
            val window = (view.context as? Activity)?.window
            val insets = window?.let { WindowCompat.getInsetsController(it, view) }
            val lightBars = insets?.isAppearanceLightStatusBars
            insets?.isAppearanceLightStatusBars = false
            insets?.isAppearanceLightNavigationBars = false
            onDispose {
                lightBars?.let {
                    insets.isAppearanceLightStatusBars = it
                    insets.isAppearanceLightNavigationBars = it
                }
                webView?.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        context: Context,
        viewModel: AdminViewModel,
        onClose: () -> Unit,
        onPickFile: (ValueCallback<Array<Uri>>) -> Unit,
    ): WebView =
        WebView(context).apply {
            setBackgroundColor(BACKGROUND_ARGB)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            // Sem WebChromeClient, alert/confirm/prompt não aparecem e o <input type="file"> não abre
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean {
                    onPickFile(filePathCallback)
                    return true
                }
            }
            addJavascriptInterface(Bridge(context, this, viewModel, onClose), "Aoi")
            loadUrl(PAGE_URL)
        }

    /** Ponte JS ↔ Kotlin (window.Aoi), igual à da Aoc mais a gravação na cloud. */
    private class Bridge(
        private val context: Context,
        private val webView: WebView,
        private val viewModel: AdminViewModel,
        private val onClose: () -> Unit,
    ) {
        private val github get() = context.getSharedPreferences("aoi_admin_github", Context.MODE_PRIVATE)

        private fun js(code: String) {
            webView.post { webView.evaluateJavascript(code, null) }
        }

        private fun toast(text: String) {
            webView.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
        }

        @JavascriptInterface
        fun close() {
            webView.post(onClose)
        }

        @JavascriptInterface
        fun ready() {
            viewModel.loadCloud(
                onResult = { userId, json ->
                    js("window.onCloud(${JSONObject.quote(userId)}, ${JSONObject.quote(json)})")
                },
                onFailure = { js("window.onCloudFailed()") },
            )
        }

        @JavascriptInterface
        fun save(key: String, json: String, stamp: String) {
            viewModel.save(
                key = key,
                json = json,
                onSaved = { js("window.onSaved(${JSONObject.quote(key)}, ${JSONObject.quote(stamp)})") },
                onFailure = { js("window.onSaveFailed()") },
            )
        }

        @JavascriptInterface
        fun saveExcel(base64Data: String, filename: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val mime = if (filename.endsWith(".xlsx")) XLSX_MIME else "text/csv"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return toast("Erro ao criar ficheiro em Transferências")
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    toast("Ficheiro guardado em Transferências")
                } else {
                    // Android 7 e 8: sem MediaStore.Downloads, fica na pasta da app
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                    File(dir, filename).writeBytes(bytes)
                    toast("Ficheiro guardado em ${dir.absolutePath}")
                }
            } catch (e: Exception) {
                toast("Erro ao guardar: ${e.message}")
            }
        }

        // Sessão do GitHub: o token fica nas SharedPreferences do telemóvel, nunca na cloud
        @JavascriptInterface
        fun setGithubToken(token: String) {
            github.edit { putString("token", token.trim()) }
        }

        @JavascriptInterface
        fun hasGithubToken(): Boolean = !github.getString("token", "").isNullOrEmpty()

        // Repos da conta (privados incluídos), devolvidos ao JS em window.onRepos(json)
        @JavascriptInterface
        fun fetchRepos() {
            val token = github.getString("token", "").orEmpty()
            Thread {
                val result = JSONObject()
                try {
                    val repos = JSONArray()
                    var page = 1
                    while (page <= 5) {
                        val conn = URL(
                            "https://api.github.com/user/repos?per_page=100&sort=updated&affiliation=owner&page=$page",
                        ).openConnection() as HttpURLConnection
                        conn.setRequestProperty("Accept", "application/vnd.github+json")
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                        conn.setRequestProperty("User-Agent", "Aoi")
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        val code = conn.responseCode
                        if (code != 200) {
                            result.put(
                                "error",
                                if (code ==
                                    401
                                ) {
                                    "Token inválido ou expirado"
                                } else {
                                    "Erro do GitHub ($code)"
                                },
                            )
                            break
                        }
                        val pageItems = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                        for (i in 0 until pageItems.length()) {
                            val r = pageItems.getJSONObject(i)
                            repos.put(
                                JSONObject().apply {
                                    put("name", r.getString("name"))
                                    put("desc", if (r.isNull("description")) "" else r.getString("description"))
                                    put("priv", r.getBoolean("private"))
                                    put("url", r.getString("html_url"))
                                    put("user", r.getJSONObject("owner").getString("login"))
                                },
                            )
                        }
                        if (pageItems.length() < 100) break
                        page++
                    }
                    if (!result.has("error")) result.put("repos", repos)
                } catch (e: Exception) {
                    result.put("error", "Sem ligação ao GitHub")
                }
                js("window.onRepos && window.onRepos(${JSONObject.quote(result.toString())})")
            }.start()
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(Intent(intent).setPackage("com.brave.browser"))
            } catch (e: ActivityNotFoundException) {
                // Sem o Brave instalado: abre no browser por defeito
                context.startActivity(intent)
            }
        }
    }

    private companion object {
        const val PAGE_URL = "file:///android_asset/admin/index.html"
        const val BACKGROUND_ARGB = 0xFF0F1115.toInt()
        val BACKGROUND = Color(BACKGROUND_ARGB)
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
