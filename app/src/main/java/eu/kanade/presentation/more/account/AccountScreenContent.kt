package eu.kanade.presentation.more.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.account.CloudSync
import eu.kanade.tachiyomi.ui.more.account.AccountViewModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Security
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AccountScreenContent(
    state: AccountViewModel.State,
    syncState: CloudSync.State,
    onNavigateBack: () -> Unit,
    onNavigateToCloudLibrary: () -> Unit,
    onRemoveLocalOnly: () -> Unit,
    onKeepLocalOnly: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(
                    if (state.username != null) MR.strings.account_title else MR.strings.account_login,
                ),
                navigateUp = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Centrado quando cabe no ecrã, com scroll quando não cabe (ex.: aviso de mangas fora da cloud)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (state.username != null) {
                    ProfileView(
                        username = state.username,
                        syncState = syncState,
                        onLogout = onLogout,
                        onContinue = onNavigateBack,
                        onCloudLibrary = onNavigateToCloudLibrary,
                        onRemoveLocalOnly = onRemoveLocalOnly,
                        onKeepLocalOnly = onKeepLocalOnly,
                    )
                } else {
                    AuthView(onLogin = onLogin, onSignUp = onSignUp)
                }
            }
        }
    }
}

@Composable
private fun AuthView(onLogin: (String, String) -> Unit, onSignUp: (String, String) -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val isLogin = selectedTab == 0

    val onAction: () -> Unit = {
        if (password.length < 6) {
            context.toast(context.stringResource(MR.strings.account_password_too_short))
        } else {
            if (isLogin) onLogin(username, password) else onSignUp(username, password)
        }
    }

    AoiLogo(size = 80.dp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(if (isLogin) MR.strings.account_welcome_back else MR.strings.account_create),
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(if (isLogin) MR.strings.account_login_summary else MR.strings.account_signup_summary),
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.outline,
    )

    Spacer(modifier = Modifier.height(32.dp))

    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = MaterialTheme.colorScheme.surface,
        divider = {},
    ) {
        Tab(selected = isLogin, onClick = { selectedTab = 0 }) {
            Text(stringResource(MR.strings.account_login), modifier = Modifier.padding(16.dp))
        }
        Tab(selected = !isLogin, onClick = { selectedTab = 1 }) {
            Text(stringResource(MR.strings.account_signup), modifier = Modifier.padding(16.dp))
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(MR.strings.account_username)) },
        leadingIcon = { Icon(MaterialSymbols.Rounded.Person, null) },
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp),
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(MR.strings.account_password)) },
        leadingIcon = { Icon(MaterialSymbols.Rounded.Security, null) },
        trailingIcon = {
            val icon = when {
                passwordVisible -> MaterialSymbols.Rounded.Visibility
                else -> MaterialSymbols.Rounded.VisibilityOff
            }
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(icon, null)
            }
        },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp),
    )

    if (!isLogin) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(MR.strings.account_no_recovery),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = onAction,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(
            text = stringResource(if (isLogin) MR.strings.account_login else MR.strings.account_create),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    TextButton(onClick = { selectedTab = if (isLogin) 1 else 0 }) {
        Text(
            text = stringResource(if (isLogin) MR.strings.account_no_account else MR.strings.account_has_account),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ProfileView(
    username: String,
    syncState: CloudSync.State,
    onLogout: () -> Unit,
    onContinue: () -> Unit,
    onCloudLibrary: () -> Unit,
    onRemoveLocalOnly: () -> Unit,
    onKeepLocalOnly: () -> Unit,
) {
    AoiLogo(size = 100.dp)
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(MR.strings.account_welcome, username),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(4.dp))
    SyncStatus(syncState)

    if (syncState.localOnly.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        LocalOnlyCard(
            mangas = syncState.localOnly,
            enabled = !syncState.running,
            onRemove = onRemoveLocalOnly,
            onKeep = onKeepLocalOnly,
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    ProfileActionButton(
        text = stringResource(MR.strings.account_cloud_library),
        icon = MaterialSymbols.Rounded.LocalLibrary,
        onClick = onCloudLibrary,
    )

    Spacer(modifier = Modifier.height(8.dp))

    ProfileActionButton(
        text = stringResource(MR.strings.account_continue),
        icon = MaterialSymbols.RoundedFilled.PlayArrow,
        onClick = onContinue,
    )

    Spacer(modifier = Modifier.height(24.dp))

    TextButton(
        onClick = onLogout,
        enabled = !syncState.running,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(MaterialSymbols.Rounded.Close, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(MR.strings.account_logout), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SyncStatus(syncState: CloudSync.State) {
    // Atualiza o "X minutes ago" de vez em quando
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val now = remember(tick, syncState.lastSyncAt) { System.currentTimeMillis() }
    val elapsed = now - syncState.lastSyncAt

    val text = when {
        syncState.running -> stringResource(MR.strings.cloud_sync_running)
        syncState.lastSyncAt == 0L -> stringResource(MR.strings.cloud_sync_never)
        elapsed < MINUTE_MS -> stringResource(MR.strings.cloud_sync_just_now)
        elapsed < HOUR_MS -> {
            val minutes = (elapsed / MINUTE_MS).toInt()
            pluralStringResource(MR.plurals.cloud_sync_minutes_ago, minutes, minutes)
        }
        elapsed < DAY_MS -> {
            val hours = (elapsed / HOUR_MS).toInt()
            pluralStringResource(MR.plurals.cloud_sync_hours_ago, hours, hours)
        }
        else -> {
            val days = (elapsed / DAY_MS).toInt()
            pluralStringResource(MR.plurals.cloud_sync_days_ago, days, days)
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LocalOnlyCard(
    mangas: List<Manga>,
    enabled: Boolean,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Text(
                text = pluralStringResource(MR.plurals.cloud_sync_local_only_title, mangas.size, mangas.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(MR.strings.cloud_sync_local_only_summary),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            mangas.take(MAX_TITLES).forEach {
                Text(
                    text = "• ${it.title}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (mangas.size > MAX_TITLES) {
                Text(text = "…", style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRemove, enabled = enabled) {
                    Text(stringResource(MR.strings.cloud_sync_local_only_remove))
                }
                TextButton(onClick = onKeep, enabled = enabled) {
                    Text(stringResource(MR.strings.cloud_sync_local_only_keep))
                }
            }
        }
    }
}

@Composable
private fun ProfileActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = Color.Gray.copy(alpha = 0.5f),
        ),
    ) {
        Icon(icon, null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(16.dp))
        Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
    }
}

// Logo como o ícone da app: quadrado azul de cantos redondos com o 青い a branco
@Composable
private fun AoiLogo(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(AOI_BLUE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mihon),
            contentDescription = null,
            // O vetor do logo tem margem própria: maior que o quadrado para o 青い ocupar o mesmo que no ícone
            modifier = Modifier.requiredSize(size * 1.25f),
            tint = Color.White,
        )
    }
}

private const val MAX_TITLES = 5
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private val AOI_BLUE = Color(0xFF0253D2)
