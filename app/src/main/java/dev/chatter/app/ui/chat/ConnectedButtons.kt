package dev.chatter.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One button in a [ConnectedButtons] row; [icon] is left out where the label says enough. With
 * [choices] the button shows a ▾ and opens them instead of doing [onClick].
 */
internal class ButtonAction(
    val label: Int,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val choices: List<ButtonChoice> = emptyList(),
    val onClick: () -> Unit = {},
)

/** One entry in the menu of a [ButtonAction] with choices. */
internal class ButtonChoice(val label: Int, val onClick: () -> Unit)

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
            val size = Modifier.weight(1f).heightIn(min = if (compact) 32.dp else 40.dp)
            if (action.choices.isNotEmpty()) {
                ChoicesButton(action, shapes, tonal, compact, size)
            } else {
                PartButton(action, shapes, tonal, compact, size, onClick = action.onClick) {
                    ButtonContent(action, compact)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartButton(
    action: ButtonAction,
    shapes: ButtonShapes,
    tonal: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val padding = if (compact) CompactRowPadding else RowPadding
    if (tonal) {
        FilledTonalButton(onClick = onClick, shapes = shapes, contentPadding = padding, modifier = modifier, content = content)
    } else {
        val color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        OutlinedButton(
            onClick = onClick,
            shapes = shapes,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
            modifier = modifier,
            content = content,
        )
    }
}

/** A button that opens its [ButtonAction.choices], marked with a ▾ after its label. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChoicesButton(action: ButtonAction, shapes: ButtonShapes, tonal: Boolean, compact: Boolean, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        PartButton(action, shapes, tonal, compact, Modifier.fillMaxWidth(), onClick = { open = true }) {
            ButtonContent(action, compact)
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.padding(start = 2.dp).size(if (compact) 16.dp else 18.dp),
            )
        }
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                action.choices.forEachIndexed { i, choice ->
                    DropdownMenuItem(
                        text = { Text(stringResource(choice.label)) },
                        shape = MenuDefaults.itemShape(i, action.choices.size).shape,
                        onClick = { open = false; choice.onClick() },
                    )
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
