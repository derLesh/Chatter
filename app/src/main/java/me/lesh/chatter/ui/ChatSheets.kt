package me.lesh.chatter.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import me.lesh.chatter.badges.Badge
import me.lesh.chatter.chat.ChatItem
import me.lesh.chatter.ui.channels.AddChannelDialog
import me.lesh.chatter.ui.channels.CombineChannelsDialog
import me.lesh.chatter.ui.channels.RenameChannelDialog
import me.lesh.chatter.ui.chat.CatchUpSheet
import me.lesh.chatter.ui.chat.ChannelMark
import me.lesh.chatter.ui.chat.ChatStyle
import me.lesh.chatter.ui.chat.EmoteCardSheet
import me.lesh.chatter.ui.chat.EmoteTap
import me.lesh.chatter.ui.chat.EmotePickerSheet
import me.lesh.chatter.ui.chat.HideBadgeDialog
import me.lesh.chatter.ui.chat.NicknameDialog
import me.lesh.chatter.ui.chat.NoteDialog
import me.lesh.chatter.ui.chat.ThreadSheet
import me.lesh.chatter.ui.chat.UserCardSheet
import me.lesh.chatter.ui.inbox.WhisperReplyDialog
import kotlinx.coroutines.launch

/**
 * What the chat screen has open over itself. The chat and its title bar set it; [ChatSheets] draws
 * it.
 */
@Stable
class ChatSheetState {
    /** The message whose user card is open. */
    var actionItem by mutableStateOf<ChatItem?>(null)
    var emoteCard by mutableStateOf<EmoteTap?>(null)
    var showPicker by mutableStateOf(false)
    var showAdd by mutableStateOf(false)
    var renameTarget by mutableStateOf<String?>(null)

    /** Null while no dialog is open; the key of the combined chat being edited, or "" for a new one. */
    var combineTarget by mutableStateOf<String?>(null)

    /** The channel a new combined chat was started from, pre-ticked in the dialog. */
    var combineWith by mutableStateOf<String?>(null)
    var nicknameTarget by mutableStateOf<ChatItem?>(null)

    /** The login whose note is being written. */
    var noteTarget by mutableStateOf<String?>(null)

    /** A badge held in the chat, whose kind the user may hide. */
    var badgeToHide by mutableStateOf<Badge?>(null)

    /** Whisper recipient from a user card: their message and their id if known. */
    var whisperTo by mutableStateOf<Pair<ChatItem, String?>?>(null)

    /** The message whose conversation is open. */
    var threadOf by mutableStateOf<ChatItem?>(null)

    /** Opens the dialog for a new combined chat, with [channel] ticked if there is one. */
    fun startCombined(channel: String?) {
        combineWith = channel
        combineTarget = ""
    }
}

/**
 * The sheets and dialogs of the chat screen: user card, conversation, whisper, nickname, note, hiding a badge, emote card
 * and picker, catch-up summary, and adding, combining and renaming channels. [threadInput] is the input inside the
 * conversation sheet.
 */
@Composable
fun ChatSheets(
    vm: MainViewModel,
    sheets: ChatSheetState,
    style: ChatStyle,
    loader: ImageLoader,
    guest: Boolean,
    partnerMarks: Map<String, ChannelMark>,
    snackbar: SnackbarHostState,
    threadInput: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modChannels by vm.modChannels.collectAsStateWithLifecycle()
    val blockedLogins by vm.blockedLogins.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()

    sheets.actionItem?.let { item ->
        UserCardSheet(
            item = item,
            canModerate = item.channel in modChannels,
            guest = guest,
            style = style,
            imageLoader = loader,
            recentMessages = { vm.recentMessagesOf(item) },
            profile = { vm.profileOf(item) },
            blocked = item.login?.lowercase() in blockedLogins,
            note = item.login?.let { notes[it.lowercase()] }.orEmpty(),
            copyFirst = settings.copyFirst,
            onBlock = vm::setBlocked,
            onBlockLogin = vm::blockByLogin,
            onNickname = { sheets.nicknameTarget = item },
            onNote = { sheets.noteTarget = item.login },
            onBadgeHidden = { badge, hidden -> if (hidden) vm.hideBadge(badge) else vm.showBadge(badge.kind) },
            // Both write into the input, which is gone while the user only reads.
            onReply = if (settings.readOnly) null else ({ vm.startReply(item) }),
            onMention = if (settings.readOnly) null else ({ vm.mention(item) }),
            onWhisper = { userId -> sheets.whisperTo = item to userId },
            onDelete = { vm.deleteMessage(item) },
            onPin = { vm.pinMessage(item, it) },
            onTimeout = { vm.timeoutUser(item, it) },
            onBan = { vm.banUser(item) },
            onDismiss = { sheets.actionItem = null },
        )
    }
    vm.catchUp?.let { CatchUpSheet(it, onDismiss = vm::closeCatchUp) }
    sheets.threadOf?.let { opened ->
        // After an answer is sent, the next one goes to the last answered message, in the same
        // conversation.
        var target by remember(opened) { mutableStateOf(opened) }
        LaunchedEffect(target, vm.replyTo) { if (vm.replyTo == null) vm.startReply(target) }
        val threadId = opened.reply?.threadId ?: opened.id
        val followed by vm.followedThreads.collectAsStateWithLifecycle()
        ThreadSheet(
            threadId = threadId,
            following = if (guest) null else remember(followed, threadId) { vm.isFollowingThread(threadId) },
            onFollow = { vm.followThread(threadId, it) },
            messages = remember(opened.channel) { vm.chat(opened.channel) },
            style = style,
            imageLoader = loader,
            partners = partnerMarks,
            onAnswer = { if (vm.startReply(it)) target = it },
            onDismiss = {
                sheets.threadOf = null
                vm.cancelReply()
            },
            input = threadInput,
        )
    }
    sheets.whisperTo?.let { (item, userId) ->
        val login = item.login
        if (login == null) sheets.whisperTo = null else {
            WhisperReplyDialog(
                name = nicknames[login.lowercase()] ?: item.displayName ?: login,
                quoted = null,
                onSend = { text ->
                    // Twitch refuses whispers for various reasons; the snackbar says which.
                    scope.launch { snackbar.showSnackbar(vm.whisperTo(login, userId, text)) }
                },
                onDismiss = { sheets.whisperTo = null },
            )
        }
    }
    sheets.nicknameTarget?.let { target ->
        val login = target.login
        if (login == null) sheets.nicknameTarget = null else {
            NicknameDialog(
                login = login,
                currentNickname = nicknames[login.lowercase()].orEmpty(),
                twitchName = target.displayName ?: login,
                onSave = { vm.setNickname(login, it) },
                onDismiss = { sheets.nicknameTarget = null },
            )
        }
    }
    sheets.noteTarget?.let { login ->
        NoteDialog(
            login = login,
            currentNote = notes[login.lowercase()].orEmpty(),
            onSave = { vm.setNote(login, it) },
            onDismiss = { sheets.noteTarget = null },
        )
    }
    sheets.badgeToHide?.let { badge ->
        HideBadgeDialog(
            badge = badge,
            imageLoader = loader,
            onHide = { vm.hideBadge(badge) },
            onDismiss = { sheets.badgeToHide = null },
        )
    }
    sheets.emoteCard?.let { tap ->
        EmoteCardSheet(
            emotes = listOf(tap.seg.emote) + tap.seg.overlays,
            channel = tap.channel,
            imageLoader = loader,
            onInsert = { vm.insertEmote(it) },
            onHide = vm::hideEmote,
            onDismiss = { sheets.emoteCard = null },
        )
    }
    if (sheets.showPicker) {
        val emoteVersion by vm.emoteVersion.collectAsStateWithLifecycle()
        val emotes = remember(vm.sendChannel, emoteVersion) { vm.emotesFor(vm.sendChannel) }
        val emoji by produceState(emptyList()) { value = vm.emoji() }
        EmotePickerSheet(
            emotes = emotes,
            emoji = emoji,
            recent = vm.recentEmotes.collectAsStateWithLifecycle().value,
            imageLoader = loader,
            onPick = vm::insertEmote,
            onPickEmoji = vm::insertEmoji,
            onReload = { vm.reloadEmotes(vm.sendChannel) },
            onDismiss = { sheets.showPicker = false },
        )
    }
    if (sheets.showAdd) {
        AddChannelDialog(
            search = vm::searchChannels,
            imageLoader = vm.imageLoader,
            onAdd = { vm.addChannel(it); sheets.showAdd = false },
            onDismiss = { sheets.showAdd = false },
        )
    }
    sheets.combineTarget?.let { key ->
        val channels by vm.channels.collectAsStateWithLifecycle()
        val groups by vm.groups.collectAsStateWithLifecycle()
        val info by vm.channelInfo.collectAsStateWithLifecycle()
        val group = groups[key]
        CombineChannelsDialog(
            channels = channels,
            info = info,
            group = group,
            imageLoader = vm.imageLoader,
            onSave = { name, members -> vm.saveGroup(group?.key, name, members) },
            onDismiss = { sheets.combineTarget = null },
            preselected = sheets.combineWith,
        )
    }
    sheets.renameTarget?.let { login ->
        val customNames by vm.customNames.collectAsStateWithLifecycle()
        RenameChannelDialog(
            login = login,
            currentName = customNames[login].orEmpty(),
            twitchName = vm.twitchName(login),
            onRename = { vm.renameChannel(login, it) },
            onDismiss = { sheets.renameTarget = null },
        )
    }
}
