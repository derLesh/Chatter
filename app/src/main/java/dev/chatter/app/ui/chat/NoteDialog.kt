package dev.chatter.app.ui.chat

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.chatter.app.R
import dev.chatter.app.chat.NoteRepository

/** Writes or changes the private note on a chatter. An empty field deletes it. */
@Composable
fun NoteDialog(
    login: String,
    currentNote: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The cursor starts at the end, so adding to a note needs no tap first.
    var note by remember { mutableStateOf(TextFieldValue(currentNote, TextRange(currentNote.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = note,
                    onValueChange = { value ->
                        note = if (value.text.length <= NoteRepository.MAX_LENGTH) value else {
                            TextFieldValue(value.text.take(NoteRepository.MAX_LENGTH), TextRange(NoteRepository.MAX_LENGTH))
                        }
                    },
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("@$login") },
                    placeholder = { Text(stringResource(R.string.note_example)) },
                    modifier = Modifier.focusRequester(focus),
                )
                Text(
                    text = stringResource(R.string.note_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note.text); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
