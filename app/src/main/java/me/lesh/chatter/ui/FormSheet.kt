package me.lesh.chatter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.lesh.chatter.R

/**
 * A bottom sheet for one short task: a title, an optional line about it, the items of [content],
 * and Cancel and [confirmLabel] fixed at the bottom, in reach of the thumb and above the keyboard.
 * The rest scrolls, so the buttons stay in view in landscape or with large text.
 *
 * Every way out slides the sheet down before [onDismiss], and the chosen action runs only after
 * that: logging out the last account would otherwise take the screen away mid-slide.
 * [focusOnOpen] takes the focus and the keyboard once the sheet is open. [content] gets `finish`,
 * which slides the sheet away and then runs its action, like the confirm button does.
 * [titleAction] sits right of the title, such as a button that deletes what the sheet edits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    text: String? = null,
    focusOnOpen: FocusRequester? = null,
    titleAction: (@Composable (finish: (() -> Unit) -> Unit) -> Unit)? = null,
    content: LazyListScope.(finish: (() -> Unit) -> Unit) -> Unit = {},
) {
    // Full height right away: a half-open sheet would put the buttons under the keyboard.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Held from the tap until the sheet is gone; a second tap would add the channel or the word twice.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Back, a tap outside or on the handle can take over the slide and end it through
    // onDismissRequest, so the action runs on whichever dismissal comes, and only once.
    val dismissed: () -> Unit = {
        val action = pendingAction
        pendingAction = null
        onDismiss()
        action?.invoke()
    }

    ModalBottomSheet(
        onDismissRequest = dismissed,
        sheetState = sheetState,
        // Dragged back up, the sheet would stay open with its buttons waiting for the close.
        sheetGesturesEnabled = pendingAction == null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // Read inside the sheet's window; outside they would be the screen's.
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val close = { action: () -> Unit ->
            if (pendingAction == null) {
                pendingAction = action
                focus.clearFocus()
                keyboard?.hide()
                scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
                    // Cancelled means Material took over the slide and dismisses the sheet itself.
                    if (cause == null) {
                        if (sheetState.isVisible) pendingAction = null else dismissed()
                    }
                }
            }
        }
        // Swiped down, the keyboard would outlive the sheet and the screen behind would jump.
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.targetValue == SheetValue.Hidden }
                .filter { it }
                .collect {
                    focus.clearFocus()
                    keyboard?.hide()
                }
        }
        // Asked before the sheet's window has the focus, the field takes it but the keyboard stays shut.
        if (focusOnOpen != null) {
            LaunchedEffect(Unit) {
                snapshotFlow { sheetState.currentValue == SheetValue.Expanded }.first { it }
                focusOnOpen.requestFocus()
                keyboard?.show()
            }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                item(key = "title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        titleAction?.invoke(close)
                    }
                }
                text?.let {
                    item(key = "text") {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                content(close)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp),
            ) {
                OutlinedButton(
                    onClick = { close {} },
                    // The default outline nearly vanishes on the sheet's surface.
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { close(onConfirm) },
                    enabled = confirmEnabled,
                    // Disabled, the default grey on grey hardly reads as a button with a label.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(confirmLabel) }
            }
        }
    }
}
