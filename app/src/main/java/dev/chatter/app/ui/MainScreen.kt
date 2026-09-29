package dev.chatter.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chatter.app.R
import dev.chatter.app.auth.AuthState
import dev.chatter.app.settings.TapAction
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.Segment
import dev.chatter.app.chat.SendLimits
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.service.ChatService
import dev.chatter.app.ui.changelog.UpdateNotesSheet
import dev.chatter.app.ui.channels.AddChannelDialog
import dev.chatter.app.ui.channels.CombineChannelsDialog
import dev.chatter.app.ui.channels.RenameChannelDialog
import dev.chatter.app.ui.channels.ChannelPages
import dev.chatter.app.ui.channels.ChannelTabBar
import dev.chatter.app.ui.channels.ChannelTopBar
import dev.chatter.app.ui.chat.ChannelMark
import dev.chatter.app.ui.chat.ChatList
import dev.chatter.app.ui.chat.MessageGesture
import dev.chatter.app.ui.chat.ChatActivity
import dev.chatter.app.ui.chat.EmoteFrameRate
import dev.chatter.app.ui.chat.SharedEmotes
import dev.chatter.app.ui.chat.noteTouches
import dev.chatter.app.ui.chat.rememberChatStyle
import dev.chatter.app.ui.chat.rememberEmoteFrameRate
import dev.chatter.app.ui.chat.EmoteCardSheet
import dev.chatter.app.ui.chat.EmotePickerSheet
import dev.chatter.app.ui.chat.GuestBar
import dev.chatter.app.ui.chat.InputBar
import dev.chatter.app.ui.chat.NicknameDialog
import dev.chatter.app.ui.chat.ThreadSheet
import dev.chatter.app.ui.chat.UserCardSheet
import dev.chatter.app.ui.inbox.InboxScreen
import dev.chatter.app.ui.inbox.WhisperReplyDialog
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun MainScreen(vm: MainViewModel, onInbox: () -> Unit, onSettings: () -> Unit) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    // What the pager swipes through: the channels, and the combined chats wherever the user put them.
    val pageKeys by vm.pages.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val unread by vm.unreadMentions.collectAsStateWithLifecycle()
    val unreadMessages by vm.unreadMessages.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Reading without an account: no field to write in, no service, no notifications to allow.
    val guest = vm.authState.collectAsStateWithLifecycle().value is AuthState.Guest
    val active by vm.activePage.collectAsStateWithLifecycle()
    val activeGroup = active?.let { groups[it] }
    // The channel the chat modes and the user's role in the title bar are about. A combined chat
    // is several, and one line of modes cannot say which of them it describes.
    val activeChannel = active?.takeIf { activeGroup == null }
    val customNames by vm.customNames.collectAsStateWithLifecycle()
    val hiddenUnread by vm.hiddenUnread.collectAsStateWithLifecycle()
    val emoteVersion by vm.emoteVersion.collectAsStateWithLifecycle()
    val modChannels by vm.modChannels.collectAsStateWithLifecycle()
    val roomStates by vm.roomStates.collectAsStateWithLifecycle()
    val roles by vm.roles.collectAsStateWithLifecycle()
    val subscribed by vm.subscribedChannels.collectAsStateWithLifecycle()
    val blockedLogins by vm.blockedLogins.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()
    val inboxUnread by vm.inboxUnread.collectAsStateWithLifecycle()
    val update by vm.availableUpdate.collectAsStateWithLifecycle()
    val sharedChats by vm.sharedChats.collectAsStateWithLifecycle()
    val chatPartners by vm.chatPartners.collectAsStateWithLifecycle()
    val partnerMarks = remember(chatPartners) {
        chatPartners.mapValues { (_, p) -> ChannelMark(p.avatarUrl, p.displayName) }
    }
    // Null while the channel on screen shares its chat with nobody; the partners Chatter can name.
    val sharedWith = activeChannel?.let { sharedChats[it] }?.map { id ->
        chatPartners[id]?.displayName ?: info.values.firstOrNull { it.id == id }?.displayName
    }?.filterNotNull()

    // What may keep the user from writing where the next message goes, for the field to say.
    val restriction = vm.sendChannel?.let { ch -> SendLimits.restriction(roomStates[ch], roles[ch], ch in subscribed) }

    val context = LocalContext.current
    // Not context.resources: only this one follows a configuration change, so a snackbar shown
    // after the language was switched is still in the language on screen.
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Where the pages are: one per channel, or a great many of them with the channels repeating,
    // which is what lets a swipe carry on past the last one. Only the settings screen can turn
    // that on, and opening it takes this screen out of the composition, so the pager is always
    // built knowing which of the two it is.
    val pages = ChannelPages(pageKeys.size, settings.carouselChannels)
    // Opening the settings takes this screen out of the composition, so the pager starts over.
    // Anchoring it to the channel the user was last on keeps them there when they come back.
    val pagerState = rememberPagerState(initialPage = pages.pageOf(pageKeys.indexOf(active).coerceAtLeast(0))) { pages.count }

    var actionItem by remember { mutableStateOf<ChatItem?>(null) }
    var emoteCard by remember { mutableStateOf<Segment.EmoteSeg?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    // Null while no dialog is open; the key of the combined chat being changed, or "" for a new one.
    var combineTarget by remember { mutableStateOf<String?>(null) }
    // The channel a new combined chat was asked for from, ticked when the dialog opens.
    var combineWith by remember { mutableStateOf<String?>(null) }
    var nicknameTarget by remember { mutableStateOf<ChatItem?>(null) }
    // Whom a whisper is being written to from their card: the message, and their id if known.
    var whisperTo by remember { mutableStateOf<Pair<ChatItem, String?>?>(null) }
    // The message whose conversation is open, or null while none is.
    var threadOf by remember { mutableStateOf<ChatItem?>(null) }

    // Animated emotes are the most expensive thing on the screen, and the battery saver is the
    // phone being asked to do less — so it stills them, the same way the setting does. Saving
    // data does too, since every frame is bytes. Both stop linked images from being fetched at
    // all; see rememberChatStyle.
    val powerSave by vm.powerSaveMode.collectAsStateWithLifecycle()
    val saveData by vm.saveData.collectAsStateWithLifecycle()
    val loader = if (settings.animatedEmotes && !powerSave && !saveData) vm.imageLoader else vm.staticImageLoader
    // A chat left alone next to the stream keeps its emotes moving, just with fewer frames.
    val activity = remember { ChatActivity() }
    val emoteFrameRate = rememberEmoteFrameRate(activity, settings.slowIdleEmotes)
    val style = rememberChatStyle(settings, nicknames, powerSave, saveData, emoteFrameRate)
    // Only while this screen is in front: the bubble draws the same shared emotes, and it is not
    // the main window going untouched that should slow them down there.
    LifecycleStartEffect(emoteFrameRate) {
        SharedEmotes.setFrameRate(emoteFrameRate)
        onStopOrDispose { SharedEmotes.setFrameRate(EmoteFrameRate.ACTIVE) }
    }

    // Only while the chat is on screen; leaving it (or the settings) lets the screen sleep again.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Keep the chat service (and with it the connection) running while there are channels.
    LifecycleStartEffect(channels.isNotEmpty(), guest) {
        if (channels.isNotEmpty() && !guest) ChatService.start(context)
        onStopOrDispose { }
    }

    // Whenever the chat comes on screen; the repository itself keeps it to once a day.
    LifecycleStartEffect(Unit) {
        vm.checkForUpdate()
        onStopOrDispose { }
    }

    // Poll live status only while the app is on screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLiveStatus() }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(guest) {
        if (!guest && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(resources, settings.haptics) {
        vm.messages.collect { message ->
            // Everything that reaches the snackbar is something that did not work out.
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Reject)
            val text = message.fill
                ?.let { resources.getString(message.text, it) }
                ?: resources.getString(message.text)
            snackbar.showSnackbar(text)
        }
    }

    // Only the mentions that arrive under the user's eyes. The others buzz through their
    // notification, and both at once would be one buzz too many.
    LaunchedEffect(lifecycleOwner, settings.haptics) {
        if (!settings.haptics) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.mentions.filter { it.seen }.collect {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }

    // Back to the channel the user was reading. The pager cannot do this itself: when the screen
    // is rebuilt after Android stopped the process, the channel list is still empty, and the page
    // it remembered is clamped to the first channel before the list arrives.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(pageKeys) {
        if (restored || pageKeys.isEmpty()) return@LaunchedEffect
        val index = pageKeys.indexOf(active ?: vm.lastChannel.value).coerceAtLeast(0)
        // From the origin, not from wherever the pager clamped itself to while the list was
        // still empty: a carousel has to start in the middle to have room to wrap both ways.
        val page = pages.pageOf(index)
        if (page != pagerState.currentPage) pagerState.scrollToPage(page)
        restored = true
    }

    // Which channel a page shows is its distance from the origin modulo the number of channels,
    // so adding or removing one would slide a different channel under the user. Anchoring the
    // pager back on the one they were reading keeps it in front of them.
    LaunchedEffect(pageKeys.size) {
        if (!restored || !pages.wrapping) return@LaunchedEffect
        val index = pageKeys.indexOf(active)
        if (index >= 0) pagerState.scrollToPage(pages.pageOf(index, pagerState.currentPage))
    }

    // A bubble reads a channel of its own and says so while it is open. Once the chat screen is
    // back in front, the page on screen is the channel again — otherwise the title bar would keep
    // naming whatever the bubble was showing.
    LifecycleStartEffect(pageKeys, restored, pages) {
        if (restored) vm.selectChannel(pages.channelAt(pagerState.currentPage)?.let(pageKeys::getOrNull))
        onStopOrDispose { }
    }

    // The page on screen defines the active channel — once it is the page the user expects. The
    // combined chats are a key as well: the one on screen may have been given other channels.
    LaunchedEffect(pagerState, pageKeys, groups, restored, pages) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { pages.channelAt(pagerState.currentPage) }
            .collect { vm.selectChannel(it?.let(pageKeys::getOrNull)) }
    }
    // Jump to a page requested by a notification tap or right after adding or combining it.
    LaunchedEffect(pageKeys, pages) {
        vm.requestedChannel.filterNotNull().collect { ch ->
            val index = pageKeys.indexOf(ch)
            if (index >= 0) {
                pagerState.scrollToPage(pages.pageOf(index, pagerState.currentPage))
                vm.requestedChannel.value = null
            }
        }
    }

    Scaffold(
        modifier = Modifier.noteTouches(activity),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val onSelect = { ch: String ->
                scope.launch {
                    pagerState.scrollToPage(pages.pageOf(pageKeys.indexOf(ch).coerceAtLeast(0), pagerState.currentPage))
                }
                Unit
            }
            val roomState = activeChannel?.let { roomStates[it] }
            val roleBadge = activeChannel?.let { ch -> roles[ch]?.let { vm.roleBadge(ch, it) } }
            if (settings.channelTabs) ChannelTabBar(
                pages = pageKeys,
                groups = groups,
                active = active,
                info = info,
                unread = unread,
                unreadMessages = unreadMessages,
                roomState = roomState,
                roleBadge = roleBadge,
                sharedWith = sharedWith,
                connection = connection,
                showUnread = settings.unreadInTitleBar,
                hiddenUnread = hiddenUnread,
                imageLoader = vm.imageLoader,
                onSelect = onSelect,
                onAdd = { showAdd = true },
                onCombine = { combineWith = it; combineTarget = "" },
                onEditGroup = { combineTarget = it },
                onRemove = vm::removeChannel,
                onRename = { renameTarget = it },
                onMove = vm::moveChannel,
                onInbox = onInbox,
                inboxUnread = inboxUnread,
                onSettings = onSettings,
                settingsBadge = update != null,
                drafts = vm.draftPages,
            ) else ChannelTopBar(
                pages = pageKeys,
                groups = groups,
                active = active,
                info = info,
                unread = unread,
                unreadMessages = unreadMessages,
                roomState = roomState,
                roleBadge = roleBadge,
                sharedWith = sharedWith,
                connection = connection,
                showUnread = settings.unreadInTitleBar,
                hiddenUnread = hiddenUnread,
                imageLoader = vm.imageLoader,
                onSelect = onSelect,
                onAdd = { showAdd = true },
                onCombine = { combineWith = null; combineTarget = "" },
                onEditGroup = { combineTarget = it },
                onRemove = vm::removeChannel,
                onRename = { renameTarget = it },
                onMove = vm::moveChannel,
                onInbox = onInbox,
                inboxUnread = inboxUnread,
                onSettings = onSettings,
                settingsBadge = update != null,
                drafts = vm.draftPages,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (pageKeys.isEmpty()) {
                EmptyState(onAdd = { showAdd = true }, modifier = Modifier.weight(1f))
            } else {
                HorizontalPager(
                    state = pagerState,
                    // A wrapping pager shows the same channel on many pages, so only the page
                    // itself tells them apart. Without one, keying on the channel is what keeps
                    // a channel's place in the list with it when the channels are reordered.
                    key = { if (pages.wrapping) it else pageKeys.getOrElse(it) { "" } },
                    modifier = Modifier.weight(1f),
                ) { page ->
                    val channel = pages.channelAt(page)?.let(pageKeys::getOrNull) ?: return@HorizontalPager
                    // Which channel each message is from is only worth showing where they mix.
                    val members = groups[channel]?.channels
                    val marks = remember(members, info) {
                        members?.associateWith { ChannelMark(info[it]?.avatarUrl, info[it]?.displayName ?: it) }
                    }
                    ChatList(
                        messages = remember(channel) { vm.chat(channel) },
                        channels = marks,
                        partners = partnerMarks,
                        readMark = vm.readMark(channel),
                        onSeen = if (channel == active) { mark -> vm.onSeen(channel, mark) } else null,
                        onMentionsSeen = if (channel == active) vm::onMentionsSeen else null,
                        style = style,
                        imageLoader = loader,
                        onGesture = { item, gesture ->
                            // Holding is not up to the settings: it is the one way to the user
                            // card that stays whatever the taps were given to.
                            val action = when (gesture) {
                                MessageGesture.Tap -> settings.messageTap
                                MessageGesture.NameTap -> settings.nameTap
                                MessageGesture.Hold -> TapAction.UserCard
                                // Reading the conversation, which is the one thing it can be.
                                MessageGesture.Thread -> {
                                    // Otherwise the field keeps its focus under the sheet, and
                                    // Android hands it the keyboard back once the sheet is gone.
                                    focusManager.clearFocus()
                                    threadOf = item
                                    vm.startReply(item)
                                    return@ChatList
                                }
                            }
                            // What cannot be answered or named (a notice, a message Twitch has
                            // not confirmed yet) opens the card instead, as every tap used to.
                            when (action) {
                                TapAction.Reply -> if (!vm.startReply(item)) actionItem = item
                                TapAction.Mention -> if (item.login != null) vm.mention(item) else actionItem = item
                                TapAction.UserCard -> actionItem = item
                                TapAction.Nothing -> Unit
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        smoothScrolling = settings.smoothScrolling,
                        onEmoteClick = { emoteCard = it },
                    )
                }
            }
            if (guest) GuestBar(onLogIn = vm::leaveGuest, modifier = Modifier.fillMaxWidth())
            else InputBar(
                value = vm.input,
                onValueChange = { activity.note(); vm.onInputChange(it) },
                enabled = vm.sendChannel != null && connection == ConnectionState.Connected,
                // The conversation has a field of its own, and two asking for the keyboard at once
                // would fight over it.
                replyTo = vm.replyTo.takeIf { threadOf == null },
                suggestions = vm.suggestions,
                imageLoader = loader,
                onSuggestion = vm::applySuggestion,
                onCancelReply = vm::cancelReply,
                onEmotePicker = { showPicker = true },
                onSend = vm::send,
                replyStarts = vm.replyStarts,
                waitUntil = vm.sendWaitUntil,
                restriction = restriction,
                modifier = Modifier.fillMaxWidth(),
                sendChannels = activeGroup?.channels.orEmpty(),
                sendChannel = vm.sendChannel,
                channelInfo = info,
                onSendChannel = vm::selectSendChannel,
            )
        }
    }

    actionItem?.let { item ->
        UserCardSheet(
            item = item,
            canModerate = item.channel in modChannels,
            guest = guest,
            style = style,
            imageLoader = loader,
            recentMessages = { vm.recentMessagesOf(item) },
            profile = { vm.profileOf(item) },
            blocked = item.login?.lowercase() in blockedLogins,
            copyFirst = settings.copyFirst,
            onBlock = vm::setBlocked,
            onBlockLogin = vm::blockByLogin,
            onNickname = { nicknameTarget = item },
            onReply = { vm.startReply(item) },
            onMention = { vm.mention(item) },
            onWhisper = { userId -> whisperTo = item to userId },
            onDelete = { vm.deleteMessage(item) },
            onTimeout = { vm.timeoutUser(item) },
            onBan = { vm.banUser(item) },
            onDismiss = { actionItem = null },
        )
    }
    threadOf?.let { opened ->
        // Answering is what the sheet is for: a sent answer leaves nothing to answer, so the next
        // one goes to the message answered last, and with it into the same conversation.
        var target by remember(opened) { mutableStateOf(opened) }
        LaunchedEffect(target, vm.replyTo) { if (vm.replyTo == null) vm.startReply(target) }
        ThreadSheet(
            threadId = opened.reply?.threadId ?: opened.id,
            messages = remember(opened.channel) { vm.chat(opened.channel) },
            style = style,
            imageLoader = loader,
            partners = partnerMarks,
            onAnswer = { if (vm.startReply(it)) target = it },
            onDismiss = {
                threadOf = null
                vm.cancelReply()
            },
        ) {
            if (guest) GuestBar(onLogIn = vm::leaveGuest, modifier = Modifier.fillMaxWidth())
            else InputBar(
                value = vm.input,
                onValueChange = { activity.note(); vm.onInputChange(it) },
                enabled = vm.sendChannel != null && connection == ConnectionState.Connected,
                replyTo = vm.replyTo,
                suggestions = vm.suggestions,
                imageLoader = loader,
                onSuggestion = vm::applySuggestion,
                onCancelReply = null,
                onEmotePicker = { showPicker = true },
                onSend = vm::send,
                replyStarts = vm.replyStarts,
                waitUntil = vm.sendWaitUntil,
                restriction = restriction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    whisperTo?.let { (item, userId) ->
        val login = item.login
        if (login == null) whisperTo = null else {
            WhisperReplyDialog(
                name = nicknames[login.lowercase()] ?: item.displayName ?: login,
                quoted = null,
                onSend = { text ->
                    // Twitch may refuse a whisper for reasons only it knows; the snackbar says which.
                    scope.launch { snackbar.showSnackbar(vm.whisperTo(login, userId, text)) }
                },
                onDismiss = { whisperTo = null },
            )
        }
    }
    nicknameTarget?.let { target ->
        val login = target.login
        if (login == null) nicknameTarget = null else {
            NicknameDialog(
                login = login,
                currentNickname = nicknames[login.lowercase()].orEmpty(),
                twitchName = target.displayName ?: login,
                onSave = { vm.setNickname(login, it) },
                onDismiss = { nicknameTarget = null },
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
        val emotes = remember(vm.sendChannel, emoteVersion) { vm.emotesFor(vm.sendChannel) }
        EmotePickerSheet(
            emotes = emotes,
            recent = settings.recentEmotes,
            imageLoader = loader,
            onPick = vm::insertEmote,
            onDismiss = { showPicker = false },
        )
    }
    if (showAdd) {
        AddChannelDialog(
            search = vm::searchChannels,
            imageLoader = vm.imageLoader,
            onAdd = { vm.addChannel(it); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
    combineTarget?.let { key ->
        val group = groups[key]
        CombineChannelsDialog(
            channels = channels,
            info = info,
            group = group,
            imageLoader = vm.imageLoader,
            onSave = { name, members -> vm.saveGroup(group?.key, name, members) },
            onDismiss = { combineTarget = null },
            preselected = combineWith,
        )
    }
    renameTarget?.let { login ->
        RenameChannelDialog(
            login = login,
            currentName = customNames[login].orEmpty(),
            twitchName = vm.twitchName(login),
            onRename = { vm.renameChannel(login, it) },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth().padding(32.dp),
    ) {
        Text(stringResource(R.string.no_channels_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.no_channels_text),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.add_channel)) }
    }
}

/** The screens the app moves between once logged in: the chat, and the two it opens over itself. */
private enum class Screen { Chat, Inbox, Settings }

@Composable
fun AppRoot(vm: MainViewModel) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showInbox by remember { mutableStateOf(false) }

    // Asked for from outside (the shortcut, a whisper notification). The inbox itself clears it
    // once it has scrolled to the tab, so it is only opened here.
    val requestedInbox by vm.requestedInbox.collectAsStateWithLifecycle()
    LaunchedEffect(requestedInbox) {
        if (requestedInbox == null) return@LaunchedEffect
        showSettings = false
        showInbox = true
    }
    // Logging the last account out leaves the settings underneath the login screen, and whoever
    // logs in next would land on the page they were last on rather than in the chat.
    LaunchedEffect(auth) {
        if (auth is AuthState.LoggedOut) {
            showSettings = false
            showInbox = false
        }
    }
    when (auth) {
        AuthState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        AuthState.LoggedOut -> LoginScreen(vm)
        is AuthState.LoggedIn, AuthState.Guest -> {
            val screen = when {
                showSettings -> Screen.Settings
                showInbox -> Screen.Inbox
                else -> Screen.Chat
            }
            val toChat = {
                showSettings = false
                showInbox = false
            }
            // The chat leaves the composition while another screen is fully over it, as it always
            // has; it is only there underneath while one slides, or is dragged away by the gesture.
            rememberPredictiveTransition(
                current = screen,
                backTo = Screen.Chat.takeIf { screen != Screen.Chat },
                onBack = toChat,
                label = "screen",
            ).AnimatedContent(
                transitionSpec = { slideBetweenScreens(forward = targetState != Screen.Chat) },
            ) { shown ->
                when (shown) {
                    Screen.Settings -> SettingsScreen(vm, onBack = toChat)
                    Screen.Inbox -> InboxScreen(
                        vm,
                        onOpenChannel = { channel ->
                            vm.requestedChannel.value = channel
                            showInbox = false
                        },
                        onBack = toChat,
                    )
                    Screen.Chat -> MainScreen(vm, onInbox = { showInbox = true }, onSettings = { showSettings = true })
                }
            }
            // Shows itself only right after an update, and only for more than a fix release.
            UpdateNotesSheet(vm)
        }
    }
}
