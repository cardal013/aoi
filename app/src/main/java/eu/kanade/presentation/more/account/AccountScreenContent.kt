package eu.kanade.presentation.more.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.more.account.AccountViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Security
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun AccountScreenContent(
    state: AccountViewModel.State,
    onNavigateBack: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = if (state.email != null) "Account" else "Login",
                navigateUp = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (state.email != null) {
                ProfileView(
                    username = state.email,
                    onLogout = onLogout,
                    onContinue = onNavigateBack,
                )
            } else {
                AuthView(onLogin = onLogin, onSignUp = onSignUp)
            }
        }
    }
}

@Composable
private fun ProfileView(
    username: String,
    onLogout: () -> Unit,
    onContinue: () -> Unit,
) {
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

    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("Continue to app")
    }

    Spacer(modifier = Modifier.height(16.dp))

    TextButton(
        onClick = onLogout,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text("Logout")
    }
}

@Composable
private fun AuthView(onLogin: (String, String) -> Unit, onSignUp: (String, String) -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

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
        singleLine = true
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
        singleLine = true
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Action Button
    Button(
        onClick = { if (selectedTab == 0) onLogin(username, password) else onSignUp(username, password) },
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
