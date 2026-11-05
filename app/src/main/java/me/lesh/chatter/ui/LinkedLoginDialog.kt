package me.lesh.chatter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 */
@Composable
fun LinkedLoginDialog(vm: MainViewModel) {
    val linked by vm.linkedLogin.collectAsStateWithLifecycle()
    val state = linked ?: return
    val context = LocalContext.current
    val busy = state is LinkedLogin.Checking || state is LinkedLogin.Saving

    AlertDialog(
        onDismissRequest = { if (!busy) vm.dismissLinkedLogin() },
        title = {
            Text(
                when (state) {
                    is LinkedLogin.Ready -> stringResource(R.string.login_link_ask, state.login)
                    is LinkedLogin.Saving -> stringResource(R.string.login_link_ask, state.login)
                    else -> stringResource(R.string.login_link_title)
                },
            )
        },
        text = {
            when (state) {
                is LinkedLogin.Checking -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.login_link_checking))
                }
                is LinkedLogin.Ready, is LinkedLogin.Saving -> Text(stringResource(R.string.login_link_hint))
                is LinkedLogin.Failed -> Text(tokenLoginError(context, state.error))
            }
        },
        confirmButton = {
            when (state) {
                is LinkedLogin.Failed -> TextButton(onClick = vm::dismissLinkedLogin) { Text(stringResource(R.string.ok)) }
                else -> TextButton(onClick = vm::acceptLinkedLogin, enabled = state is LinkedLogin.Ready) {
                    Text(stringResource(R.string.login_token_confirm))
                }
            }
        },
        dismissButton = if (state is LinkedLogin.Failed) null else {
            { TextButton(onClick = vm::dismissLinkedLogin, enabled = state !is LinkedLogin.Saving) { Text(stringResource(R.string.cancel)) } }
        },
    )
}
