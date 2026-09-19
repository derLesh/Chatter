package dev.chatter.app.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.ImageLoader
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelInfo
import kotlin.math.roundToInt

/** Fixed row height, which is what turns a drag distance into a number of positions moved. */
private val ROW_HEIGHT = 64.dp

/**
 * Manages the channel list in one place: reorder by dragging the handle, rename, remove, and
 * add a new one.
 */
@Composable
fun ManageChannelsPage(
    channels: List<String>,
    info: Map<String, ChannelInfo>,
    muted: Set<String>,
    hiddenUnread: Set<String>,
    imageLoader: ImageLoader,
    onMove: (String, Int) -> Unit,
    onNotify: (String, Boolean) -> Unit,
    onUnreadVisible: (String, Boolean) -> Unit,
    onRename: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { ROW_HEIGHT.toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        channels.forEachIndexed { index, login ->
            val held = dragging == login
            // While one row is held, the others slide out of its way to show where it would land.
            val shift = if (dragging == null || held) 0f else {
                val from = channels.indexOf(dragging)
                val to = (from + (dragOffset / rowHeightPx).roundToInt()).coerceIn(0, channels.lastIndex)
                when {
                    index in (from + 1)..to -> -rowHeightPx
                    index in to..<from -> rowHeightPx
                    else -> 0f
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = tileShape(index, channels.size),
                shadowElevation = if (held) 8.dp else 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .zIndex(if (held) 1f else 0f)
                    .graphicsLayer { translationY = if (held) dragOffset else shift },
            ) {
                ChannelRow(
                    login = login,
                    info = info[login],
                    notify = login !in muted,
                    inTitleBar = login !in hiddenUnread,
                    imageLoader = imageLoader,
                    onNotify = { onNotify(login, it) },
                    onUnreadVisible = { onUnreadVisible(login, it) },
                    onRename = { onRename(login) },
                    onRemove = { onRemove(login) },
                    dragHandle = Modifier.pointerInput(login, channels) {
                        detectDragGestures(
                            onDragStart = { dragging = login; dragOffset = 0f },
                            onDragEnd = {
                                val moved = (dragOffset / rowHeightPx).roundToInt()
                                if (moved != 0) onMove(login, moved)
                                dragging = null
                                dragOffset = 0f
                            },
                            onDragCancel = { dragging = null; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                        )
                    },
                )
            }
        }
    }
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.add_channel))
    }
}

@Composable
private fun ChannelRow(
    login: String,
    info: ChannelInfo?,
    notify: Boolean,
    inTitleBar: Boolean,
    imageLoader: ImageLoader,
    onNotify: (Boolean) -> Unit,
    onUnreadVisible: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    dragHandle: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
    ) {
        Icon(
            Icons.Default.Menu,
            contentDescription = stringResource(R.string.reorder_channel),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = dragHandle.padding(horizontal = 12.dp),
        )
        ChannelAvatar(info, imageLoader, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val name = info?.displayName ?: login
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            // Only worth spelling out the login when a rename made it unrecognizable.
            if (!name.equals(login, ignoreCase = true)) {
                Text(
                    login,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        // Lit means mentions here notify; dimmed means they only count towards the badge.
        RowAction(
            icon = Icons.Default.Notifications,
            label = if (notify) R.string.channel_notify_on else R.string.channel_notify_off,
            tint = if (notify) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            onClick = { onNotify(!notify) },
        )
        // Renaming, removing and the title bar option share a menu: four icons in a row would
        // leave no room for the name on a narrow screen.
        var menu by remember { mutableStateOf(false) }
        Box {
            RowAction(Icons.Default.MoreVert, R.string.channel_options, { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename_channel)) },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.channel_in_title_bar)) },
                    leadingIcon = { if (inTitleBar) Icon(Icons.Default.Check, null) },
                    onClick = { menu = false; onUnreadVisible(!inTitleBar) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_channel)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun RowAction(
    icon: ImageVector,
    label: Int,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = stringResource(label), tint = tint)
    }
}

/** Rounded like the settings tiles: strong corners outside, slight ones between rows. */
private fun tileShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 24.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}
