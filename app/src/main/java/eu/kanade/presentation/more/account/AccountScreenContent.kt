package eu.kanade.presentation.more.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.more.account.AccountViewModel
import eu.kanade.tachiyomi.util.system.toast
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Security
import mihon.icons.materialsymbols.rounded.Security
import mihon.icons.materialsymbols.rounded.Storage
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun AccountScreenContent(
    state: AccountViewModel.State,
    syncState: AccountViewModel.SyncState,
    onNavigateBack: () -> Unit,
    onNavigateToCloudLibrary: () -> Unit,
    onUpdateAccount: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onLogout: () -> Unit,
    onClearFailed: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = if (state.username != null) "Account" else "Login",
                navigateUp = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (state.username != null) {
                    ProfileView(
                        username = state.username,
                        syncStatus = syncState.status,
                        onLogout = onLogout,
                        onContinue = onNavigateBack,
                        onCloudLibrary = onNavigateToCloudLibrary,
                        onUpdateAccount = onUpdateAccount,
                    )
                } else {
                    AuthView(onLogin = onLogin, onSignUp = onSignUp)
                }
            }

            if (syncState.status == AccountViewModel.SyncStatus.Syncing) {
                SyncProgressOverlay(syncState.progress)
            }

            if (syncState.failedMangas.isNotEmpty()) {
                SyncErrorDialog(
                    failedMangas = syncState.failedMangas,
                    onDismiss = onClearFailed
                )
            }
        }
    }
}

@Composable
private fun AuthView(onLogin: (String, String) -> Unit, onSignUp: (String, String) -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val onAction = {
        if (password.length < 6) {
            context.toast("Your password needs to be at least 6 characters")
        } else {
            if (selectedTab == 0) onLogin(username, password) else onSignUp(username, password)
        }
        Unit
    }

    // Header
    Icon(
        painter = painterResource(R.drawable.ic_mihon),
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = if (selectedTab == 0) "Welcome Back" else "Create Account",
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = if (selectedTab == 0) "Log in to continue" else "Sign up to get started",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.outline
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Tabs
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = MaterialTheme.colorScheme.surface,
        divider = {}
    ) {
        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
            Text("Log In", modifier = Modifier.padding(16.dp))
        }
        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
            Text("Sign Up", modifier = Modifier.padding(16.dp))
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Inputs
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Username") },
        leadingIcon = { Icon(MaterialSymbols.Rounded.Person, null) },
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp)
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Password") },
        leadingIcon = { Icon(MaterialSymbols.Rounded.Security, null) },
        trailingIcon = {
            val icon = if (passwordVisible) MaterialSymbols.Rounded.Visibility else MaterialSymbols.Rounded.VisibilityOff
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(icon, null)
            }
        },
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp)
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Action Button
    Button(
        onClick = onAction,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text(if (selectedTab == 0) "Log In" else "Create Account", color = MaterialTheme.colorScheme.onPrimary)
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Switch link
    TextButton(onClick = { selectedTab = if (selectedTab == 0) 1 else 0 }) {
        Text(
            text = if (selectedTab == 0) "Don't have an account? Sign Up" else "Already have an account? Log In",
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SyncProgressOverlay(progress: Pair<Int, Int>?) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Sync,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Syncing with cloud",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Please do not leave this screen until finished",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (progress != null) {
                val (current, total) = progress
                val percent = if (total > 0) current.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress = { percent },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Processing: $current / $total mangas",
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SyncErrorDialog(failedMangas: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(MaterialSymbols.Rounded.Error, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Sync Finished with Errors") },
        text = {
            Column {
                Text("${failedMangas.size} mangas failed to sync. You might want to check your connection and try again.")
                if (failedMangas.size <= 5) {
                    Spacer(Modifier.height(8.dp))
                    failedMangas.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Composable
private fun ProfileView(
    username: String,
    syncStatus: AccountViewModel.SyncStatus,
    onLogout: () -> Unit,
    onContinue: () -> Unit,
    onCloudLibrary: () -> Unit,
    onUpdateAccount: () -> Unit,
) {
    val isSyncing = syncStatus == AccountViewModel.SyncStatus.Syncing

    Icon(
        painter = painterResource(R.drawable.ic_mihon),
        contentDescription = null,
        modifier = Modifier.size(100.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Welcome, $username",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(48.dp))

    ProfileActionButton(
        text = "My Cloud Library",
        icon = MaterialSymbols.Rounded.LocalLibrary,
        enabled = !isSyncing,
        onClick = onCloudLibrary
    )

    Spacer(modifier = Modifier.height(8.dp))

    ProfileActionButton(
        text = "Update Account",
        icon = MaterialSymbols.Rounded.Sync,
        enabled = !isSyncing,
        onClick = onUpdateAccount
    )

    Spacer(modifier = Modifier.height(8.dp))

    ProfileActionButton(
        text = "Continue to app",
        icon = MaterialSymbols.RoundedFilled.PlayArrow,
        onClick = onContinue
    )

    Spacer(modifier = Modifier.height(24.dp))

    TextButton(
        onClick = onLogout,
        enabled = !isSyncing,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(MaterialSymbols.Rounded.Close, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text("Logout", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProfileActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color.Gray.copy(alpha = 0.5f)
        )
    ) {
        Icon(icon, null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(16.dp))
        // ExtraBold to make it look clickable without background
        Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
    }
}
