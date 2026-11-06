package me.lesh.chatter.ui.inbox

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.ui.FormSheet

/**
 * Answers a whisper, with the original shown as a bubble above the input since the inbox is the
 * only place the conversation exists. Without [quoted] it starts a new conversation, as from the
 * user card.
 */
@Composable
fun WhisperReplySheet(
    name: String,
    quoted: String?,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    FormSheet(
        title = stringResource(R.string.whisper_reply_title, name),
        confirmLabel = stringResource(R.string.send),
        onConfirm = { onSend(text) },
        onDismiss = onDismiss,
        confirmEnabled = text.isNotBlank(),
        focusOnOpen = focus,
    ) { finish ->
        if (quoted != null) item(key = "quoted") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                // A chat bubble's tail corner, so it reads as their message and not as a hint.
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                modifier = Modifier.padding(top = 16.dp, end = 48.dp),
            ) {
                Text(
                    quoted,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        item(key = "input") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(if (quoted != null) R.string.whisper_reply_hint else R.string.whisper_new_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) finish { onSend(text) } }),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
            )
        }
    }
}
