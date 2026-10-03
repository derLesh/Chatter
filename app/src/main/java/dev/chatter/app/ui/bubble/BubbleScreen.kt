package dev.chatter.app.ui.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.R
import dev.chatter.app.auth.AuthState
import dev.chatter.app.chat.Segment
import dev.chatter.app.chat.SendLimits
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.channels.ChannelAvatar
import dev.chatter.app.ui.channels.connectionStatus
import dev.chatter.app.ui.chat.ChannelMark
import dev.chatter.app.ui.chat.ChatList
import dev.chatter.app.ui.chat.EmoteCardSheet
import dev.chatter.app.ui.chat.EmotePickerSheet
import dev.chatter.app.ui.chat.InputBar
import dev.chatter.app.ui.chat.rememberChatStyle

/**
 * A single channel's chat sized for a bubble: a one-line header, the message list and the same
 * input as the main window.
 */
@Composable
fun BubbleScreen(vm: MainViewModel, channel: String?) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val emoteVersion by vm.emoteVersion.collectAsStateWithLifecycle()
    val chatPartners by vm.chatPartners.collectAsStateWithLifecycle()
    val roomStates by vm.roomStates.collectAsStateWithLifecycle()
    val roles by vm.roles.collectAsStateWithLifecycle()
    val subscribed by vm.subscribedChannels.collectAsStateWithLifecycle()
    val partnerMarks = remember(chatPartners) {
        chatPartners.mapValues { (_, p) -> ChannelMark(p.avatarUrl, p.displayName) }
    }

    // Same as the chat screen: either saver stills emotes and stops linked images.
    val powerSave by vm.powerSaveMode.collectAsStateWithLifecycle()
    val saveData by vm.saveData.collectAsStateWithLifecycle()
    val style = rememberChatStyle(settings, nicknames, powerSave, saveData)
    val loader = if (settings.animatedEmotes && !powerSave && !saveData) vm.imageLoader else vm.staticImageLoader
    var emoteCard by remember { mutableStateOf<Segment.EmoteSeg?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // A Surface, not just a background color: it sets the content color, otherwise text stays black
    // and disappears in a dark theme.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            if (channel == null || auth !is AuthState.LoggedIn) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.bubble_unavailable),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Column
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                ChannelAvatar(info[channel], loader, 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info[channel]?.displayName ?: channel,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    if (connection != ConnectionState.Connected) {
                        Text(
                            stringResource(connectionStatus(connection)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()

            ChatList(
                messages = remember(channel) { vm.chat(channel) },
                style = style,
                imageLoader = loader,
                // No user card: a bubble is too small for the sheet, and answering is what one does
                // there.
                onGesture = { item, _ -> vm.startReply(item) },
                modifier = Modifier.weight(1f),
                smoothScrolling = settings.smoothScrolling,
                onEmoteClick = { emoteCard = it },
                partners = partnerMarks,
                onMentionsSeen = vm::onMentionsSeen,
            )
            InputBar(
                value = vm.input,
                onValueChange = vm::onInputChange,
                enabled = connection == ConnectionState.Connected,
                replyTo = vm.replyTo,
                suggestions = vm.suggestions,
                imageLoader = loader,
                onSuggestion = vm::applySuggestion,
                onCancelReply = vm::cancelReply,
                onEmotePicker = { showPicker = true },
                onSend = vm::send,
                replyStarts = vm.replyStarts,
                waitUntil = vm.sendWaitUntil,
                restriction = vm.sendChannel?.let { ch -> SendLimits.restriction(roomStates[ch], roles[ch], ch in subscribed) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    emoteCard?.let { seg ->
        EmoteCardSheet(
            emotes = listOf(seg.emote) + seg.overlays,
            imageLoader = loader,
            onInsert = { vm.insertEmote(it) },
            onDismiss = { emoteCard = null },
        )
    }
    if (showPicker) {
        val emotes = remember(channel, emoteVersion) { vm.emotesFor(channel) }
        EmotePickerSheet(
            emotes = emotes,
            recent = settings.recentEmotes,
            imageLoader = loader,
            onPick = vm::insertEmote,
            onDismiss = { showPicker = false },
        )
    }
}
