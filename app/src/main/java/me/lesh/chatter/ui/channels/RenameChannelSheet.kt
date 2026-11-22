package me.lesh.chatter.ui.channels

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.ui.FormSheet

/** Sets a custom channel name. An empty field restores the Twitch name, shown as placeholder. */
@Composable
fun RenameChannelSheet(
    login: String,
    currentName: String,
    twitchName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    val focus = remember { FocusRequester() }

    FormSheet(
        title = stringResource(R.string.rename_channel),
        confirmLabel = stringResource(R.string.save),
        onConfirm = { onRename(name) },
        onDismiss = onDismiss,
        focusOnOpen = focus,
    ) { finish ->
        item(key = "name") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(login) },
                placeholder = { Text(twitchName) },
                supportingText = { Text(stringResource(R.string.rename_channel_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish { onRename(name) } }),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
            )
        }
    }
}
