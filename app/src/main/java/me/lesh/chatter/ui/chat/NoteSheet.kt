package me.lesh.chatter.ui.chat

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.chat.NoteRepository
import me.lesh.chatter.ui.FormSheet

/** Writes or changes the private note on a chatter. An empty field deletes it. */
@Composable
fun NoteSheet(
    login: String,
    currentNote: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The cursor starts at the end, so adding to a note needs no tap first.
    var note by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentNote, TextRange(currentNote.length)))
    }
    val focus = remember { FocusRequester() }

    FormSheet(
        title = stringResource(R.string.note_title),
        confirmLabel = stringResource(R.string.save),
        onConfirm = { onSave(note.text) },
        onDismiss = onDismiss,
        focusOnOpen = focus,
    ) {
        item(key = "note") {
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
                supportingText = { Text(stringResource(R.string.note_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
            )
        }
    }
}
