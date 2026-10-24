package eu.kanade.tachiyomi.ui.more.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.account.UsersRepository
import eu.kanade.tachiyomi.util.system.toast
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Security
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.VisibilityOff
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Ecrã Users, aberto em Account (só para admins, só na app; não vai para o site).
 * Lista com pesquisa por username; cada linha tem o username, a data de criação, o interruptor de Admin
 * (desligado na própria conta) e o botão de mudar a palavra-passe (desligado nos outros admins).
 */
class UsersScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = metroViewModel<UsersViewModel>()
        val state by viewModel.state.collectAsState()
        var changingPasswordOf by remember { mutableStateOf<UsersRepository.User?>(null) }

        LaunchedEffect(viewModel) {
            viewModel.events.collect { event ->
                val message = when (event) {
                    UsersViewModel.Event.PasswordChanged -> MR.strings.users_password_changed
                    UsersViewModel.Event.PasswordChangeFailed -> MR.strings.users_password_failed
                    UsersViewModel.Event.PasswordOfAdmin -> MR.strings.users_password_admin
                    UsersViewModel.Event.AdminChangeFailed -> MR.strings.users_admin_failed
                }
                context.toast(message)
            }
        }

        Scaffold(
            topBar = {
                SearchToolbar(
                    searchQuery = state.query,
                    onChangeSearchQuery = { viewModel.onQueryChange(it.orEmpty()) },
                    placeholderText = stringResource(MR.strings.users_search_hint),
                    navigateUp = navigator::pop,
                    onClickCloseSearch = navigator::pop,
                )
            },
        ) { contentPadding ->
            when {
                state.isLoading && state.users.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.hasError -> EmptyScreen(
                    stringRes = MR.strings.users_error,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.action_retry,
                            icon = MaterialSymbols.Rounded.Refresh,
                            onClick = viewModel::retry,
                        ),
                    ),
                )
                state.users.isEmpty() -> EmptyScreen(
                    stringRes = MR.strings.no_results_found,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> LazyColumn(contentPadding = contentPadding) {
                    items(state.users, key = { it.userId }) { user ->
                        UserRow(
                            user = user,
                            isCurrentUser = user.userId == state.currentUserId,
                            onSetAdmin = { makeAdmin -> viewModel.setAdmin(user, makeAdmin) },
                            onChangePassword = { changingPasswordOf = user },
                        )
                    }
                }
            }
        }

        changingPasswordOf?.let { user ->
            ChangePasswordDialog(
                username = user.username.orEmpty(),
                onDismissRequest = { changingPasswordOf = null },
                onConfirm = { newPassword ->
                    viewModel.setPassword(user, newPassword)
                    changingPasswordOf = null
                },
            )
        }
    }
}

/**
 * Uma conta: username e data de criação; interruptor de Admin (não dá para tirar o próprio) e
 * botão de mudar a palavra-passe (não dá para a de outro admin).
 */
@Composable
private fun UserRow(
    user: UsersRepository.User,
    isCurrentUser: Boolean,
    onSetAdmin: (Boolean) -> Unit,
    onChangePassword: () -> Unit,
) {
    val canToggleAdmin = !isCurrentUser
    val isOtherAdmin = user.isAdmin && !isCurrentUser
    val canChangePassword = !isOtherAdmin

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = user.username ?: stringResource(MR.strings.users_no_username),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(MR.strings.users_created, formatDate(user.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(MR.strings.users_admin), style = MaterialTheme.typography.labelSmall)
            Switch(checked = user.isAdmin, onCheckedChange = onSetAdmin, enabled = canToggleAdmin)
        }
        IconButton(onClick = onChangePassword, enabled = canChangePassword) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Security,
                contentDescription = stringResource(MR.strings.users_change_password),
            )
        }
    }
}

/** Data de criação da conta no formato do telemóvel (ex.: 8 Sep 2026). */
private fun formatDate(epochMilliseconds: Long): String {
    val date = Instant.ofEpochMilli(epochMilliseconds).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

/**
 * Pede a palavra-passe nova (mínimo 6 caracteres) e confirma. Avisa que a conta sai da sessão em
 * todos os aparelhos.
 */
@Composable
private fun ChangePasswordDialog(
    username: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val isLongEnough = newPassword.length >= UsersViewModel.MIN_PASSWORD_LENGTH

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.users_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(MR.strings.users_change_password_summary, username))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(stringResource(MR.strings.users_new_password)) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    MaterialSymbols.Rounded.VisibilityOff
                                } else {
                                    MaterialSymbols.Rounded.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    supportingText = { Text(stringResource(MR.strings.account_password_too_short)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(newPassword) }, enabled = isLongEnough) {
                Text(stringResource(MR.strings.users_change_password_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
