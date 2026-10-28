package dev.chatter.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One button in a [ConnectedButtons] row; [icon] is left out where the label says enough. */
internal class ButtonAction(val label: Int, val icon: ImageVector? = null, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * A row of buttons joined into one group (Material 3 Expressive): round outer corners, tight inner
 * ones, and the pressed button turns squarer. [tonal] fills them, otherwise they are outlined;
 * [compact] makes a lower row with smaller text, for actions that should not take much room.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConnectedButtons(
    actions: List<ButtonAction>,
    tonal: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = modifier.fillMaxWidth(),
    ) {
        actions.forEachIndexed { i, action ->
            val joined = when {
                actions.size == 1 -> null
                i == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                i == actions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val shapes = joined?.let { ButtonShapes(it.shape, it.pressedShape) } ?: ButtonDefaults.shapes()
            val padding = if (compact) CompactRowPadding else RowPadding
            val size = Modifier.weight(1f).heightIn(min = if (compact) 32.dp else 40.dp)
            if (tonal) {
                FilledTonalButton(onClick = action.onClick, shapes = shapes, contentPadding = padding, modifier = size) {
                    ButtonContent(action, compact)
                }
            } else {
                val color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                OutlinedButton(
                    onClick = action.onClick,
                    shapes = shapes,
                    contentPadding = padding,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
                    modifier = size,
                ) {
                    ButtonContent(action, compact)
                }
            }
        }
    }
}

@Composable
private fun ButtonContent(action: ButtonAction, compact: Boolean) {
    action.icon?.let {
        Icon(it, contentDescription = null, modifier = Modifier.size(if (compact) 16.dp else 18.dp))
        Spacer(Modifier.width(6.dp))
    }
    Text(
        stringResource(action.label),
        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val RowPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
private val CompactRowPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
