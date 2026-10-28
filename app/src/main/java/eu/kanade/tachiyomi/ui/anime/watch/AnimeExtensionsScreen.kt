package eu.kanade.tachiyomi.ui.anime.watch

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.anime.AnimeExtensions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/** Repositórios e extensões de anime (do Aniyomi): instalar, atualizar, confiar e desinstalar. */
class AnimeExtensionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val extensions = remember { context.appGraph.animeExtensions }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.anime_extensions),
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = { extensions.refreshAvailable() }) {
                            Icon(MaterialSymbols.Rounded.Refresh, null)
                        }
                    },
                )
            },
        ) { padding ->
            AnimeExtensionsList(extensions, padding)
        }
    }
}

/** Lista de repositórios e extensões; também aparece no ecrã do anime enquanto não há nenhuma instalada. */
@Composable
fun AnimeExtensionsList(
    extensions: AnimeExtensions,
    contentPadding: PaddingValues = PaddingValues(),
    header: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by extensions.state.collectAsState()
    val scope = rememberCoroutineScope()
    var repoUrl by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val invalidText = stringResource(MR.strings.anime_ext_repo_invalid)

    // Sem nenhuma extensão: pergunta se instala as de origem (uma vez por entrada no ecrã)
    var defaultsAsked by rememberSaveable { mutableStateOf(false) }
    var defaultsJob by remember { mutableStateOf<Job?>(null) }
    var defaultsProgress by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    val missingDefaults = remember(state) { extensions.missingDefaults(state) }
    val noneInstalled = state.installed.isEmpty() && state.untrusted.isEmpty()

    LaunchedEffect(Unit) {
        extensions.reloadInstalled()
        extensions.refreshAvailable()
    }

    if (noneInstalled && missingDefaults.isNotEmpty() && !defaultsAsked && !state.loading) {
        AlertDialog(
            onDismissRequest = { defaultsAsked = true },
            title = { Text(stringResource(MR.strings.anime_ext_defaults_title)) },
            text = {
                Text(stringResource(MR.strings.anime_ext_defaults_text, missingDefaults.joinToString("\n") { "• ${it.name}" }))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        defaultsAsked = true
                        val toInstall = missingDefaults
                        defaultsJob = scope.launch {
                            try {
                                extensions.installAll(toInstall) { index, ext ->
                                    defaultsProgress = Triple(index + 1, toInstall.size, ext.name)
                                }
                            } finally {
                                defaultsProgress = null
                                defaultsJob = null
                            }
                        }
                    },
                ) { Text(stringResource(MR.strings.anime_ext_defaults_install)) }
            },
            dismissButton = {
                TextButton(onClick = { defaultsAsked = true }) { Text(stringResource(MR.strings.anime_ext_defaults_later)) }
            },
        )
    }

    val q = query.trim()
    val installed = state.installed.filter { extensionMatches(q, it.name, it.lang, it.pkg) }
    val untrusted = state.untrusted.filter { extensionMatches(q, it.name, "", it.pkg) }
    val notInstalled = state.available.filter { av ->
        !av.nsfw && state.installed.none { it.pkg == av.pkg } && state.untrusted.none { it.pkg == av.pkg } &&
            extensionMatches(q, av.name, av.lang, av.pkg)
    }

    LazyColumn(contentPadding = contentPadding) {
        header?.let { item { it() } }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        defaultsProgress?.let { (index, total, name) ->
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(MR.strings.anime_ext_defaults_installing, index, total, name),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { defaultsJob?.cancel() }) { Text(stringResource(MR.strings.action_cancel)) }
                }
            }
        }

        item { Header(stringResource(MR.strings.anime_ext_repos)) }
        items(state.repos) { repo ->
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(repo, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { extensions.removeRepo(repo) }) { Icon(MaterialSymbols.Rounded.Delete, null) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = repoUrl,
                    onValueChange = { repoUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(MR.strings.anime_ext_repo_hint)) },
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (extensions.addRepo(repoUrl)) repoUrl = "" else Toast.makeText(context, invalidText, Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(MR.strings.anime_ext_add_repo)) }
            }
        }
        state.error?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(MaterialSymbols.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(MaterialSymbols.Rounded.Close, null) }
                    }
                },
                placeholder = { Text(stringResource(MR.strings.anime_ext_search)) },
            )
        }

        if (untrusted.isNotEmpty()) {
            item { Header(stringResource(MR.strings.anime_ext_untrusted)) }
            items(untrusted, key = { "u" + it.pkg }) { ext ->
                ExtensionRow(name = ext.name, detail = ext.versionName, icon = null) {
                    OutlinedButton(onClick = { extensions.uninstall(ext.pkg) }) { Text(stringResource(MR.strings.anime_ext_uninstall)) }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { extensions.trust(ext) }) { Text(stringResource(MR.strings.anime_ext_trust)) }
                }
            }
        }

        if (installed.isNotEmpty()) {
            item { Header(stringResource(MR.strings.anime_ext_installed)) }
            items(installed, key = { "i" + it.pkg }) { ext ->
                val update = state.available.find { it.pkg == ext.pkg && it.versionCode > ext.versionCode }
                ExtensionRow(name = ext.name, detail = "${ext.lang} · ${ext.versionName}", icon = ext.icon) {
                    if (update != null) {
                        Button(onClick = { scope.launch { installOrToast(extensions, update, context) } }) {
                            Text(stringResource(MR.strings.anime_ext_update))
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    OutlinedButton(onClick = { extensions.uninstall(ext.pkg) }) { Text(stringResource(MR.strings.anime_ext_uninstall)) }
                }
            }
        }

        item { Header(stringResource(MR.strings.anime_ext_available)) }
        if (state.repos.isEmpty()) {
            item { Text(stringResource(MR.strings.anime_ext_none), modifier = Modifier.padding(16.dp)) }
        } else if (q.isNotEmpty() && installed.isEmpty() && untrusted.isEmpty() && notInstalled.isEmpty()) {
            item { Text(stringResource(MR.strings.anime_ext_no_match), modifier = Modifier.padding(16.dp)) }
        }
        items(notInstalled, key = { "a" + it.pkg }) { ext ->
            ExtensionRow(name = ext.name, detail = "${ext.lang} · ${ext.versionName}", iconUrl = ext.iconUrl) {
                Button(onClick = { scope.launch { installOrToast(extensions, ext, context) } }) {
                    Text(stringResource(MR.strings.anime_ext_install))
                }
            }
        }
    }
}

/** Pesquisa da lista: pelo nome, pela língua ou pelo pacote. Vazia = todas. */
private fun extensionMatches(query: String, name: String, lang: String, pkg: String): Boolean =
    query.isEmpty() ||
        name.contains(query, ignoreCase = true) ||
        lang.equals(query, ignoreCase = true) ||
        pkg.contains(query, ignoreCase = true)

private suspend fun installOrToast(extensions: AnimeExtensions, ext: AnimeExtensions.Available, context: android.content.Context) {
    try {
        extensions.install(ext)
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ExtensionRow(
    name: String,
    detail: String,
    icon: android.graphics.drawable.Drawable? = null,
    iconUrl: String? = null,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            icon != null -> Image(icon.toBitmap().asImageBitmap(), null, Modifier.size(40.dp))
            iconUrl != null -> AsyncImage(model = iconUrl, contentDescription = null, modifier = Modifier.size(40.dp))
            else -> Spacer(Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row { actions() }
    }
}
