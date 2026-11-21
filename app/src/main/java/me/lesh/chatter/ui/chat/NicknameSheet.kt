package me.lesh.chatter.ui.chat

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

/** Sets a chatter's nickname. An empty field restores the Twitch name, shown as placeholder. */
@Composable
fun NicknameSheet(
    login: String,
    currentNickname: String,
    twitchName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(currentNickname) }
    val focus = remember { FocusRequester() }

    FormSheet(
        title = stringResource(R.string.nickname_title),
        confirmLabel = stringResource(R.string.save),
        onConfirm = { onSave(name) },
        onDismiss = onDismiss,
        focusOnOpen = focus,
    ) { finish ->
        item(key = "nickname") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("@$login") },
                placeholder = { Text(twitchName) },
                supportingText = { Text(stringResource(R.string.nickname_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish { onSave(name) } }),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
            )
        }
    }
}
