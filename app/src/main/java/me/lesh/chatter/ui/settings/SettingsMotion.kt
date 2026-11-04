package me.lesh.chatter.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// How the settings move: pages come in group by group, held tiles round their corners.

/** Where the row inside a tile reports its presses, so the tile can change its shape. */
internal val LocalTilePresses = compositionLocalOf<MutableInteractionSource?> { null }

/** [clickable] for a row inside a settings tile: the tile rounds its corners while it is held. */
internal fun Modifier.tileClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val presses = LocalTilePresses.current ?: remember { MutableInteractionSource() }
    clickable(interactionSource = presses, indication = ripple(), enabled = enabled, onClick = onClick)
}

/**
 * Hands out the order in which a page's parts come in. Only the home does this, as the settings
 * open; a page opened from it already slides in.
 */
internal class PageEntrance(val animate: Boolean) {
    private var next = 0

    fun take(): Int = next++
}

internal val LocalPageEntrance = compositionLocalOf<PageEntrance?> { null }

/** Later parts wait no longer than this many steps, so a long page is not still arriving. */
private const val MAX_ENTRANCE_STEP = 6
private const val ENTRANCE_STEP_MS = 35L

/** Fades [content] in and lifts it into place, a little after the parts above it. */
@Composable
internal fun EnterInOrder(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val entrance = LocalPageEntrance.current
    val order = remember { entrance?.takeIf { it.animate }?.take() }
    val shown = remember { Animatable(if (order == null) 1f else 0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        if (order == null) return@LaunchedEffect
        delay(minOf(order, MAX_ENTRANCE_STEP) * ENTRANCE_STEP_MS)
        shown.animateTo(1f, spec)
    }
    Box(
        modifier.graphicsLayer {
            alpha = shown.value.coerceIn(0f, 1f)
            translationY = (1f - shown.value) * 16.dp.toPx()
        },
    ) { content() }
}

/** A switch whose thumb shows a check while on, so on and off differ by more than colour. */
@Composable
internal fun SettingsSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
    )
}
