package dev.chatter.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import dev.chatter.app.R

/**
 * Asks for one entry to put on a list. [title] and [hint] say which list, so the same dialog
 * serves the highlight words, the muted ones and the image sites.
 *
 * Starting from [initial] turns it into the same dialog for changing an entry that is already
 * there — the same field, filled in, with [confirmLabel] saying so.
 */
@Composable
fun AddKeywordDialog(
    title: Int,
    hint: Int,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    label: Int = R.string.keyword_word,
    confirmLabel: Int = R.string.add,
) {
    // The cursor belongs behind what is already there: an entry opened for changing is one the
    // user wants to carry on typing at, not one to type in front of.
    var word by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val confirm = {
        if (word.text.isNotBlank()) {
            onAdd(word.text)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
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
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = word.text.isNotBlank()) { Text(stringResource(confirmLabel)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
