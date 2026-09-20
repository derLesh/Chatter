package dev.chatter.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dev.chatter.app.R
import dev.chatter.app.net.HelixChannelSearch
import kotlinx.coroutines.delay

/**
 * Blocks someone who is not on screen to be long-pressed. Twitch has no user search, so the
 * channel search stands in for one: it finds anyone who streams, and a name typed in full works
 * for everyone else.
 */
@Composable
fun BlockUserDialog(
    search: suspend (String) -> List<HelixChannelSearch>,
    alreadyBlocked: Set<String>,
    imageLoader: ImageLoader,
    onBlock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<HelixChannelSearch>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        delay(300) // debounce typing
        results = search(query).filterNot { it.login.lowercase() in alreadyBlocked }
    }

    val confirm = { login: String -> onBlock(login); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.block_user_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.block_user_name)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank()) confirm(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp)) {
                    items(results, key = { it.id }) { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { confirm(r.login) }
                                .padding(vertical = 8.dp),
                        ) {
                            AsyncImage(
                                model = r.thumbnailUrl,
                                contentDescription = null,
                                imageLoader = imageLoader,
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(r.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm(query) }, enabled = query.isNotBlank()) {
                Text(stringResource(R.string.action_block))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Unblocking is easy to hit by accident and undoes a deliberate decision, so it asks first. */
@Composable
fun ConfirmUnblockDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unblock_confirm_title)) },
        text = { Text(stringResource(R.string.unblock_confirm_text, name)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(R.string.action_unblock)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
