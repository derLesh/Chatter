package me.lesh.chatter.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The app's snackbar, drawn as a pill in the colors of the chat's status pill instead of Material's
 * inverted box, which glares white over a dark chat.
 */
@Composable
fun MessagePillHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { MessagePill(it) }
}

/**
 * A message for [MessagePillHost] whose [highlight], the name of what it is about, stands out.
 * [failure]: something did not work; a message that says it did gets a check instead of the warning.
 */
class PillMessage(
    override val message: String,
    val highlight: String? = null,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    val failure: Boolean = true,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

/**
 * Without an action the message gets an icon: the warning for a failure, which a plain text
 * message counts as, and a check for a change that worked. One with an action (undo) has none.
 */
@Composable
private fun MessagePill(data: SnackbarData) {
    val colors = MaterialTheme.colorScheme
    val visuals = data.visuals
    val action = visuals.actionLabel
    Surface(
        // A full pill at one line, still round at the corners when the text wraps.
        shape = RoundedCornerShape(24.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = if (action == null) 12.dp else 20.dp, end = if (action == null) 20.dp else 8.dp),
        ) {
            if (action == null) {
                if ((visuals as? PillMessage)?.failure == false) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                } else {
                    // Only the icon in red, like the status pill: it sits where the user writes.
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                highlighted(visuals.message, (visuals as? PillMessage)?.highlight),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp),
            )
            if (action != null) {
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = data::performAction) { Text(action) }
            }
        }
    }
}

private fun highlighted(message: String, highlight: String?): AnnotatedString {
    if (highlight.isNullOrEmpty()) return AnnotatedString(message)
    val start = message.indexOf(highlight)
    if (start < 0) return AnnotatedString(message)
    return buildAnnotatedString {
        append(message)
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + highlight.length)
    }
}
