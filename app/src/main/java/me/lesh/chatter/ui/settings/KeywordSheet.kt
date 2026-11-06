package me.lesh.chatter.ui.settings

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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import me.lesh.chatter.R
import me.lesh.chatter.ui.FormSheet

/**
 * Asks for one list entry; [title] and [hint] name the list (highlight words, muted words, image
 * sites). With [initial] it edits an existing entry, and [confirmLabel] says so.
 */
@Composable
fun KeywordSheet(
    title: Int,
    hint: Int,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    label: Int = R.string.keyword_word,
    confirmLabel: Int = R.string.add,
) {
    // Cursor at the end, to continue typing.
    var word by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }

    FormSheet(
        title = stringResource(title),
        confirmLabel = stringResource(confirmLabel),
        onConfirm = { onAdd(word.text) },
        onDismiss = onDismiss,
        focusOnOpen = focus,
        confirmEnabled = word.text.isNotBlank(),
    ) { finish ->
        item(key = "field") {
            OutlinedTextField(
                value = word,
                onValueChange = { word = it },
                singleLine = true,
                label = { Text(stringResource(label)) },
                supportingText = { Text(stringResource(hint)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (word.text.isNotBlank()) finish { onAdd(word.text) } }),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).focusRequester(focus),
            )
        }
    }
}
