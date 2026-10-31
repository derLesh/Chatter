package me.lesh.chatter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import me.lesh.chatter.R
import me.lesh.chatter.channels.displayName
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.settings.TapAction
import me.lesh.chatter.ui.channels.AppButtons
import me.lesh.chatter.ui.channels.ChannelAvatar
import me.lesh.chatter.ui.channels.ChannelStatus
import me.lesh.chatter.ui.channels.GroupAvatar
import me.lesh.chatter.ui.channels.GroupStatus
import me.lesh.chatter.ui.chat.ChannelMark
import me.lesh.chatter.ui.chat.ChatList
import me.lesh.chatter.ui.chat.ChatStyle
import me.lesh.chatter.ui.chat.MessageGesture
import me.lesh.chatter.ui.chat.PinnedBar
import me.lesh.chatter.ui.theme.barColor

/** From this window width on, the chat gets a second pane beside it. Material's medium width. */
private val WIDE_WINDOW = 600.dp

/** Whether the window has room for two panes. The window's width, not the screen's: they differ in split screen. */
@Composable
internal fun isWideWindow(): Boolean =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= WIDE_WINDOW

/**
 * A second chat beside the one in front. It has no input of its own: a tap opens the card, and
 * answering from there brings this chat to the front, where the input is. Its bar carries the inbox
 * and settings buttons, on the right where a thumb reaches them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SideChat(
    vm: MainViewModel,
    page: String,
    style: ChatStyle,
    imageLoader: ImageLoader,
    partners: Map<String, ChannelMark>,
    smoothScrolling: Boolean,
    sheets: ChatSheetState,
    onThread: (ChatItem) -> Unit,
    inboxUnread: Int,
    onSettings: () -> Unit,
    settingsBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val roomStates by vm.roomStates.collectAsStateWithLifecycle()
    val roles by vm.roles.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val group = groups[page]
    val marks = remember(group, info) {
        group?.channels?.associateWith { ChannelMark(info[it]?.avatarUrl, info[it]?.displayName ?: it) }
    }

    DisposableEffect(page) {
        vm.setSideShown(true)
        onDispose { vm.setSideShown(false) }
    }

    Column(modifier) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.barColor),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (group != null) GroupAvatar(group.channels, info, imageLoader, 34.dp)
                    else ChannelAvatar(info[page], imageLoader, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            group?.displayName(info) ?: info[page]?.displayName ?: page,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (group != null) GroupStatus(connection, group, info)
                        else ChannelStatus(
                            connection = connection,
                            state = roomStates[page],
                            roleBadge = roles[page]?.let { vm.roleBadge(page, it) },
                            sharedWith = null,
                            imageLoader = imageLoader,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = vm::bringSideToFront) {
                    Icon(painterResource(R.drawable.ic_swap_panes), stringResource(R.string.beside_to_front))
                }
                IconButton(onClick = { vm.showBeside(null) }) {
                    Icon(Icons.Default.Close, stringResource(R.string.beside_close))
                }
                AppButtons(inboxUnread, onInbox = { vm.openInboxBeside() }, onSettings, settingsBadge)
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatList(
                messages = remember(page) { vm.chat(page) },
                channels = marks,
                partners = partners,
                readMark = vm.readMark(page),
                onSeen = { mark -> vm.onSeen(page, mark) },
                onMentionsSeen = vm::onMentionsSeen,
                style = style,
                imageLoader = imageLoader,
                onGesture = { item, gesture ->
                    when (gesture) {
                        MessageGesture.Thread -> onThread(item)
                        MessageGesture.Hold -> sheets.actionItem = item
                        // An answer or a mention goes through the card, since either moves this
                        // chat to the front; a stray tap while reading should not.
                        MessageGesture.Tap, MessageGesture.NameTap -> {
                            val action = if (gesture == MessageGesture.Tap) vm.settings.value.messageTap else vm.settings.value.nameTap
                            if (action != TapAction.Nothing) sheets.actionItem = item
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                smoothScrolling = smoothScrolling,
                onEmoteClick = { sheets.emoteCard = it },
                onBadgeHold = { sheets.badgeToHide = it },
            )
            PinnedBar(
                pin = pins[page]?.takeIf { it.item.id !in vm.hiddenPins },
                style = style,
                imageLoader = imageLoader,
                onHide = vm::hidePin,
                onUnpin = vm::unpinMessage,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }
    }
}
