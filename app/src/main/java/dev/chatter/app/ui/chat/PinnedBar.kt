package dev.chatter.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.chat.PinnedMessage

/**
 * The message pinned in a channel, floating over the top of its chat and cut to about two lines. A
 * tap shows all of it, with buttons to put it away and to unpin it. Null lets the last one leave.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PinnedBar(
    pin: PinnedMessage?,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onHide: (PinnedMessage) -> Unit,
    onUnpin: (PinnedMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    // The bar leaves with the message it showed, not with an empty one.
    var shown by remember { mutableStateOf(pin) }
    if (pin != null) shown = pin
    AnimatedVisibility(
        visible = pin != null,
        enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeIn(motion.defaultEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeOut(motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        shown?.let { Bar(it, style, imageLoader, onHide, onUnpin) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Bar(
    pin: PinnedMessage,
    style: ChatStyle,
    imageLoader: ImageLoader,
    onHide: (PinnedMessage) -> Unit,
    onUnpin: (PinnedMessage) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    var expanded by remember(pin.item.id) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Squeezes and rounds while held, and springs back: Material 3 Expressive's press.
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec())
    val corner by animateDpAsState(if (pressed) 28.dp else 20.dp, motion.fastSpatialSpec())
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, motion.defaultSpatialSpec())
    // Two lines of chat text and the row's padding.
    val collapsedHeight = with(LocalDensity.current) { (style.fontSize * 2.9f).sp.toDp() } + 8.dp
    val item = remember(pin.item) { pin.item.copy(historical = false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(corner),
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = stringResource(if (expanded) R.string.pin_show_less else R.string.pin_show_all),
                ) { expanded = !expanded }
                .animateContentSize(motion.defaultSpatialSpec())
                .padding(vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_push_pin),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (pin.pinnedBy.isEmpty()) stringResource(R.string.pinned) else stringResource(R.string.pinned_by, pin.pinnedBy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).rotate(arrow),
                )
            }
            Box(if (expanded) Modifier else Modifier.heightIn(max = collapsedHeight).clipToBounds()) {
                MessageRow(item, style, imageLoader, onGesture = null)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                ConnectedButtons(
                    listOf(
                        ButtonAction(R.string.action_hide_pin) { onHide(pin) },
                        ButtonAction(R.string.action_unpin, ImageVector.vectorResource(R.drawable.ic_push_pin)) { onUnpin(pin) },
                    ),
                    tonal = true,
                    compact = true,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
                )
            }
        }
    }
}
