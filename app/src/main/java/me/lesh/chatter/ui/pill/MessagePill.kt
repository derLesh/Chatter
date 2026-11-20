package me.lesh.chatter.ui.pill

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The app's snackbar, drawn as a [Pill]. Show messages on it with [showPill]. */
@Composable
fun MessagePillHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val visuals = data.visuals
        val action = visuals.actionLabel
        Pill(
            content = (visuals as? PillMessage)?.content ?: PillContent(visuals.message, PillTone.Plain),
            modifier = Modifier.padding(horizontal = 16.dp),
            action = action?.let { { TextButton(onClick = data::performAction) { Text(it) } } },
        )
    }
}

/** A message for [MessagePillHost]; see [showPill]. */
class PillMessage(
    val content: PillContent,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals {
    override val message: String get() = content.text
    override val withDismissAction: Boolean = false
}

/**
 * Shows [content] as a pill and waits until it is gone. One with an action stays up longer, so
 * there is time to reach for it.
 */
suspend fun SnackbarHostState.showPill(
    content: PillContent,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
): SnackbarResult = showSnackbar(PillMessage(content, actionLabel, duration))

suspend fun SnackbarHostState.showPill(text: String, tone: PillTone): SnackbarResult = showPill(PillContent(text, tone))
