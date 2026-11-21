package me.lesh.chatter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lesh.chatter.R

/** Where a login handed over by the Chatter website stands; see [MainViewModel.offerLinkedToken]. */
sealed interface LinkedLogin {
    val token: String

    data class Checking(override val token: String) : LinkedLogin
    data class Ready(override val token: String, val login: String) : LinkedLogin
    data class Saving(override val token: String, val login: String) : LinkedLogin
    data class Failed(override val token: String, val error: Throwable) : LinkedLogin
}

/**
 * Asks before a token from the website logs in. Any page can open a `chatter://login` link, so
 * without the question a stranger's link could log the user into the stranger's account.
 *
 * The sheet is gone while the login is saved; a failure brings it back with the reason.
 */
@Composable
fun LinkedLoginSheet(vm: MainViewModel) {
    val linked by vm.linkedLogin.collectAsStateWithLifecycle()
    val state = linked ?: return
    if (state is LinkedLogin.Saving) return
    val context = LocalContext.current

    FormSheet(
        title = when (state) {
            is LinkedLogin.Ready -> stringResource(R.string.login_link_ask, state.login)
            else -> stringResource(R.string.login_link_title)
        },
        text = when (state) {
            is LinkedLogin.Ready -> stringResource(R.string.login_link_hint)
            is LinkedLogin.Failed -> tokenLoginError(context, state.error)
            else -> null
        },
        confirmLabel = stringResource(if (state is LinkedLogin.Failed) R.string.ok else R.string.login_token_confirm),
        confirmEnabled = state !is LinkedLogin.Checking,
        onConfirm = { if (state is LinkedLogin.Failed) vm.dismissLinkedLogin() else vm.acceptLinkedLogin() },
        // Not in onDismiss, which also runs before onConfirm: dismissing revokes the token.
        onDismiss = {},
        onCancel = vm::dismissLinkedLogin,
    ) {
        if (state is LinkedLogin.Checking) {
            item(key = "checking") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.login_link_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
