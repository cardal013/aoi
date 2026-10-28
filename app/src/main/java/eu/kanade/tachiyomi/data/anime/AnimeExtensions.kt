package eu.kanade.tachiyomi.data.anime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import logcat.LogPriority
import mihon.data.dalvik.DelegateLastClassLoaderCompat
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Extensões de anime do Aniyomi (APKs com a feature "tachiyomi.animeextension"), à parte das de manga do Mihon.
 * Os repositórios e as assinaturas em que se confia ficam neste aparelho. Instalar abre o instalador do Android;
 * quando um pacote entra, sai ou muda, a lista volta a carregar sozinha.
 */
@Inject
@SingleIn(AppScope::class)
class AnimeExtensions(
    private val context: Context,
    private val network: NetworkHelper,
    private val json: Json,
) {

    data class Installed(
        val pkg: String,
        val name: String,
        val versionName: String,
        val versionCode: Long,
        val lang: String,
        val sources: List<AnimeSource>,
        val icon: Drawable?,
    )

    data class Untrusted(val pkg: String, val name: String, val versionName: String, val signature: String)

    data class Available(
        val pkg: String,
        val name: String,
        val versionName: String,
        val versionCode: Long,
        val lang: String,
        val nsfw: Boolean,
        val apkUrl: String,
        val iconUrl: String,
    )

    data class State(
        val installed: List<Installed> = emptyList(),
        val untrusted: List<Untrusted> = emptyList(),
        val available: List<Available> = emptyList(),
        val repos: List<String> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val prefs = context.getSharedPreferences("aoi_anime_extensions", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State(repos = repos()))
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                reloadInstalled()
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        reloadInstalled()
    }

    val sources: List<AnimeSource> get() = _state.value.installed.flatMap { it.sources }

    fun source(id: Long): AnimeSource? = sources.find { it.id == id }

    // ---------- Instaladas ----------

    fun reloadInstalled() {
        scope.launch {
            val pm = context.packageManager
            val results = pm.getInstalledPackages(PACKAGE_FLAGS)
                .filter { pkg -> pkg.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE } }
                .mapNotNull { load(it) }
            _state.update { st ->
                st.copy(
                    installed = results.filterIsInstance<Installed>().sortedBy { it.name.lowercase() },
                    untrusted = results.filterIsInstance<Untrusted>(),
                )
            }
        }
    }

    private fun load(pkgInfo: PackageInfo): Any? {
        val pm = context.packageManager
        val appInfo = pkgInfo.applicationInfo ?: return null
        val pkg = pkgInfo.packageName
        val name = appInfo.metaData?.getString(METADATA_NAME)
            ?: pm.getApplicationLabel(appInfo).toString().substringAfter("Aniyomi: ")
        val versionName = pkgInfo.versionName ?: return null
        val libVersion = appInfo.metaData?.getInt(METADATA_EXTENSION_LIB)?.takeUnless { it == 0 }?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) {
            logcat(LogPriority.WARN) { "Extensão de anime $pkg com lib $libVersion, não suportada" }
            return null
        }
        val signature = signatures(pkgInfo)?.lastOrNull() ?: return null
        if ("$pkg:$signature" !in trusted()) {
            // Instalada a partir do Aoi (repositório que o utilizador pôs): confia logo, sem mais um passo
            if (pkg !in requested()) return Untrusted(pkg, name, versionName, signature)
            prefs.edit {
                putStringSet(KEY_TRUSTED, trusted() + "$pkg:$signature")
                putStringSet(KEY_REQUESTED, requested() - pkg)
            }
        }

        val classLoader = try {
            DelegateLastClassLoaderCompat(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Não deu para carregar $pkg" }
            return null
        }
        val sources = try {
            appInfo.metaData!!.getString(METADATA_SOURCE_CLASS)!!
                .split(";")
                .map { it.trim().let { cls -> if (cls.startsWith(".")) pkg + cls else cls } }
                .flatMap {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor().newInstance()) {
                        is AnimeSource -> listOf(obj)
                        is AnimeSourceFactory -> obj.createSources()
                        else -> error("Tipo de fonte desconhecido: ${obj.javaClass}")
                    }
                }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Não deu para carregar as fontes de $pkg" }
            return null
        }
        val langs = sources.map { it.lang }.toSet()
        return Installed(
            pkg = pkg,
            name = name,
            versionName = versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo),
            lang = if (langs.size == 1) langs.first() else "all",
            sources = sources,
            icon = appInfo.loadIcon(pm),
        )
    }

    private fun signatures(pkgInfo: PackageInfo): List<String>? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pkgInfo.signingInfo ?: return null
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
        return raw?.map { Hash.sha256(it.toByteArray()) }
    }

    private fun trusted(): Set<String> = prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty()

    private fun requested(): Set<String> = prefs.getStringSet(KEY_REQUESTED, emptySet()).orEmpty()

    fun trust(untrusted: Untrusted) {
        prefs.edit { putStringSet(KEY_TRUSTED, trusted() + "${untrusted.pkg}:${untrusted.signature}") }
        reloadInstalled()
    }

    fun uninstall(pkg: String) {
        val intent = Intent(Intent.ACTION_DELETE, "package:$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ---------- Repositórios ----------

    private fun repos(): List<String> = prefs.getStringSet(KEY_REPOS, emptySet()).orEmpty().sorted()

    /**
     * Aceita o link do index.min.json, da pasta onde ele está, do repositório no GitHub ou um
     * `aniyomi://add-repo?url=…`; sem `https://` também serve.
     */
    fun addRepo(url: String): Boolean {
        val base = AnimeRepoUrl.base(url) ?: return false
        prefs.edit { putStringSet(KEY_REPOS, repos().toSet() + base) }
        _state.update { it.copy(repos = repos()) }
        refreshAvailable()
        return true
    }

    fun removeRepo(base: String) {
        prefs.edit { putStringSet(KEY_REPOS, repos().toSet() - base) }
        _state.update { it.copy(repos = repos()) }
        refreshAvailable()
    }

    fun refreshAvailable() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val errors = mutableListOf<String>()
            val available = repos().flatMap { base ->
                try {
                    val body = network.client.newCall(GET("$base/index.min.json")).awaitSuccess().body.string()
                    parseRepo(base, json.parseToJsonElement(body).jsonArray)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Repositório de anime $base" }
                    errors += base
                    emptyList()
                }
            }
            _state.update {
                it.copy(
                    available = available.distinctBy { ext -> ext.pkg }.sortedBy { ext -> ext.name.lowercase() },
                    loading = false,
                    error = errors.takeIf { e -> e.isNotEmpty() }?.joinToString(prefix = "Sem resposta de: "),
                )
            }
        }
    }

    private fun parseRepo(base: String, items: JsonArray): List<Available> = items.mapNotNull { item ->
        val o = item.jsonObject
        val pkg = o["pkg"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val apk = o["apk"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val versionName = o["version"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val libVersion = versionName.substringBeforeLast('.').toDoubleOrNull()
        if (libVersion == null || libVersion !in SUPPORTED_LIB_VERSIONS) return@mapNotNull null
        Available(
            pkg = pkg,
            name = o["name"]?.jsonPrimitive?.content.orEmpty().substringAfter("Aniyomi: "),
            versionName = versionName,
            versionCode = o["code"]?.jsonPrimitive?.long ?: 0,
            lang = o["lang"]?.jsonPrimitive?.content.orEmpty(),
            nsfw = o["nsfw"]?.jsonPrimitive?.content == "1",
            apkUrl = "$base/apk/$apk",
            iconUrl = "$base/icon/$pkg.png",
        )
    }

    /** Descarrega o APK e abre o instalador do Android. */
    suspend fun install(ext: Available) {
        prefs.edit { putStringSet(KEY_REQUESTED, requested() + ext.pkg) }
        val dir = File(context.cacheDir, "anime_extensions").apply { mkdirs() }
        val file = File(dir, "${ext.pkg}.apk")
        network.client.newCall(GET(ext.apkUrl)).awaitSuccess().use { response ->
            file.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private companion object {
        const val EXTENSION_FEATURE = "tachiyomi.animeextension"
        const val METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
        const val METADATA_NAME = "aniyomix.name"
        const val METADATA_EXTENSION_LIB = "aniyomix.extensionLib"
        val SUPPORTED_LIB_VERSIONS = listOf(14.0, 16.0, 17.0)
        const val KEY_REPOS = "repos"
        const val KEY_TRUSTED = "trusted"
        const val KEY_REQUESTED = "requested"

        @Suppress("DEPRECATION")
        val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    }
}
