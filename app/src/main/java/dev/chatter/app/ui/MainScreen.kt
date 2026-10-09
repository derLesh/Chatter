package dev.chatter.app.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chatter.app.R
import dev.chatter.app.auth.AuthState
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.Segment
import dev.chatter.app.chat.SendLimits
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.service.ChatService
import dev.chatter.app.settings.TapAction
import dev.chatter.app.ui.changelog.UpdateNotesSheet
import dev.chatter.app.ui.channels.AddChannelDialog
import dev.chatter.app.ui.channels.ChannelPages
import dev.chatter.app.ui.channels.ChannelTabBar
import dev.chatter.app.ui.channels.ChannelTopBar
import dev.chatter.app.ui.channels.CombineChannelsDialog
import dev.chatter.app.ui.channels.OfferUndoRemoval
import dev.chatter.app.ui.channels.RenameChannelDialog
import dev.chatter.app.ui.chat.ChannelMark
import dev.chatter.app.ui.chat.ChatActivity
import dev.chatter.app.ui.chat.ChatList
import dev.chatter.app.ui.chat.EmoteCardSheet
import dev.chatter.app.ui.chat.EmoteFrameRate
import dev.chatter.app.ui.chat.EmotePickerSheet
import dev.chatter.app.ui.chat.GuestBar
import dev.chatter.app.ui.chat.InputBar
import dev.chatter.app.ui.chat.MessageGesture
import dev.chatter.app.ui.chat.NicknameDialog
import dev.chatter.app.ui.chat.SharedEmotes
import dev.chatter.app.ui.chat.ThreadSheet
import dev.chatter.app.ui.chat.UserCardSheet
import dev.chatter.app.ui.chat.noteTouches
import dev.chatter.app.ui.chat.rememberChatStyle
import dev.chatter.app.ui.chat.rememberEmoteFrameRate
import dev.chatter.app.ui.inbox.InboxScreen
import dev.chatter.app.ui.inbox.WhisperReplyDialog
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun MainScreen(vm: MainViewModel, onInbox: () -> Unit, onSettings: () -> Unit) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    // The pager's pages: channels and combined chats in the user's order.
    val pageKeys by vm.pages.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val unread by vm.unreadMentions.collectAsStateWithLifecycle()
    val unreadMessages by vm.unreadMessages.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Guests have no input, no service and no notifications to allow.
    val guest = vm.authState.collectAsStateWithLifecycle().value is AuthState.Guest
    val active by vm.activePage.collectAsStateWithLifecycle()
    val activeGroup = active?.let { groups[it] }
    // The channel the title bar's chat modes and role describe; a combined chat has several.
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
    // The nameable Shared Chat partners of the channel on screen; null if it shares with nobody.
    val sharedWith = activeChannel?.let { sharedChats[it] }?.map { id ->
        chatPartners[id]?.displayName ?: info.values.firstOrNull { it.id == id }?.displayName
    }?.filterNotNull()

    // What may keep the user from writing where the next message goes, shown in the input.
    val restriction = vm.sendChannel?.let { ch -> SendLimits.restriction(roomStates[ch], roles[ch], ch in subscribed) }

    val context = LocalContext.current
    // Not context.resources: only this follows configuration changes, so a snackbar after a
    // language switch uses the new language.
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    OfferUndoRemoval(vm, snackbar)
    // One page per channel, or many with the channels repeating so swiping wraps around. Only the
    // settings can change that, and opening them removes this screen from composition, so the pager
    // is always created with the current mode.
    val pages = ChannelPages(pageKeys.size, settings.carouselChannels)
    // Opening the settings recreates the pager, so it starts at the channel the user was on.
    val pagerState = rememberPagerState(initialPage = pages.pageOf(pageKeys.indexOf(active).coerceAtLeast(0))) { pages.count }

    var actionItem by remember { mutableStateOf<ChatItem?>(null) }
    var emoteCard by remember { mutableStateOf<Segment.EmoteSeg?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    // Null while no dialog is open; the key of the combined chat being edited, or "" for a new one.
    var combineTarget by remember { mutableStateOf<String?>(null) }
    // The channel a new combined chat was started from, pre-ticked in the dialog.
    var combineWith by remember { mutableStateOf<String?>(null) }
    var nicknameTarget by remember { mutableStateOf<ChatItem?>(null) }
    // Whisper recipient from a user card: their message and their id if known.
    var whisperTo by remember { mutableStateOf<Pair<ChatItem, String?>?>(null) }
    // The message whose conversation is open, or null.
    var threadOf by remember { mutableStateOf<ChatItem?>(null) }

    // Animated emotes are the most expensive part of the screen; battery saver and data saving
    // still them like the setting does, and also stop linked images (see rememberChatStyle).
    val powerSave by vm.powerSaveMode.collectAsStateWithLifecycle()
    val saveData by vm.saveData.collectAsStateWithLifecycle()
    val loader = if (settings.animatedEmotes && !powerSave && !saveData) vm.imageLoader else vm.staticImageLoader
    // An idle chat next to a stream keeps animating, at a lower frame rate.
    val activity = remember { ChatActivity() }
    val emoteFrameRate = rememberEmoteFrameRate(activity, settings.slowIdleEmotes)
    val style = rememberChatStyle(settings, nicknames, powerSave, saveData, emoteFrameRate)
    // Only while this screen is in front: the bubble draws the same shared emotes, and the main
    // window being idle should not slow them there.
    LifecycleStartEffect(emoteFrameRate) {
        SharedEmotes.setFrameRate(emoteFrameRate)
        onStopOrDispose { SharedEmotes.setFrameRate(EmoteFrameRate.ACTIVE) }
    }

    // Only while the chat is on screen.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Keeps the chat service and connection running while there are channels.
    LifecycleStartEffect(channels.isNotEmpty(), guest) {
        if (channels.isNotEmpty() && !guest) ChatService.start(context)
        onStopOrDispose { }
    }

    // Each time the chat comes on screen; the repository limits it to once a day.
    LifecycleStartEffect(Unit) {
        vm.checkForUpdate()
        onStopOrDispose { }
    }

    // Polls live status only while the app is on screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLiveStatus() }
    }

    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(resources, settings.haptics) {
        vm.messages.collect { message ->
            // Everything in the snackbar is a failure.
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Reject)
            val text = message.fill
                ?.let { resources.getString(message.text, it) }
                ?: resources.getString(message.text)
            snackbar.showSnackbar(text)
        }
    }

    // Only mentions that arrive on screen; others vibrate through their notification.
    LaunchedEffect(lifecycleOwner, settings.haptics) {
        if (!settings.haptics) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.mentions.filter { it.seen }.collect {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }

    // Restores the channel the user was reading. The pager cannot: after process death the channel
    // list is empty at first and its remembered page gets clamped to the first channel.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(pageKeys) {
        if (restored || pageKeys.isEmpty()) return@LaunchedEffect
        val index = pageKeys.indexOf(active ?: vm.lastChannel.value).coerceAtLeast(0)
        // From the origin, not from where the pager clamped itself: a carousel starts in the middle
        // to wrap both ways.
        val page = pages.pageOf(index)
        if (page != pagerState.currentPage) pagerState.scrollToPage(page)
        restored = true
    }

    // A page's channel is its offset from the origin modulo the channel count, so adding or
    // removing one would shift channels; re-anchor on the one being read.
    LaunchedEffect(pageKeys.size) {
        if (!restored || !pages.wrapping) return@LaunchedEffect
        val index = pageKeys.indexOf(active)
        if (index >= 0) pagerState.scrollToPage(pages.pageOf(index, pagerState.currentPage))
    }

    // A bubble shows its own channel in the title bar while open; when the chat screen is back, the
    // page on screen is the channel again.
    LifecycleStartEffect(pageKeys, restored, pages) {
        if (restored) vm.selectChannel(pages.channelAt(pagerState.currentPage)?.let(pageKeys::getOrNull))
        onStopOrDispose { }
    }

    // The page on screen sets the active channel, once it is the expected page. Combined chats are
    // keys too, since the one on screen may have new channels.
    LaunchedEffect(pagerState, pageKeys, groups, restored, pages) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { pages.channelAt(pagerState.currentPage) }
            .collect { vm.selectChannel(it?.let(pageKeys::getOrNull)) }
    }
    // Scrolls to a page requested by a notification, or just added or combined.
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
                    // A wrapping pager shows a channel on many pages, so only the page index
                    // identifies them. Otherwise keying on the channel keeps its state when
                    // channels are reordered.
                    key = { if (pages.wrapping) it else pageKeys.getOrElse(it) { "" } },
                    modifier = Modifier.weight(1f),
                ) { page ->
                    val channel = pages.channelAt(page)?.let(pageKeys::getOrNull) ?: return@HorizontalPager
                    // The channel of each message is only shown where channels mix.
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
                            // Holding always opens the user card, whatever taps are set to.
                            val action = when (gesture) {
                                MessageGesture.Tap -> settings.messageTap
                                MessageGesture.NameTap -> settings.nameTap
                                MessageGesture.Hold -> TapAction.UserCard
                                // A tap on the reply line opens the conversation.
                                MessageGesture.Thread -> {
                                    // Otherwise the input keeps focus under the sheet and gets the
                                    // keyboard back when it closes.
                                    focusManager.clearFocus()
                                    threadOf = item
                                    vm.startReply(item)
                                    return@ChatList
                                }
                            }
                            // Messages that cannot be answered or mentioned (a notice, an
                            // unconfirmed message) open the card.
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
                // The conversation sheet has its own input; two inputs would fight over the
                // keyboard.
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
        // After an answer is sent, the next one goes to the last answered message, in the same
        // conversation.
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
                    // Twitch refuses whispers for various reasons; the snackbar says which.
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

/** The screens after login: the chat and the two it opens over itself. */
private enum class Screen { Chat, Inbox, Settings }

@Composable
fun AppRoot(vm: MainViewModel) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showInbox by remember { mutableStateOf(false) }

    // Requested from outside (shortcut, whisper notification). The inbox clears it after scrolling
    // to the tab.
    val requestedInbox by vm.requestedInbox.collectAsStateWithLifecycle()
    LaunchedEffect(requestedInbox) {
        if (requestedInbox == null) return@LaunchedEffect
        showSettings = false
        showInbox = true
    }
    // After the last logout the settings would stay underneath the login screen, and the next login
    // would land there instead of in the chat.
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
            // Once, right after the first login; guests get no notifications to allow.
            val introSeen by vm.notificationIntroSeen.collectAsStateWithLifecycle()
            val context = LocalContext.current
            if (auth is AuthState.LoggedIn && introSeen == false && !notificationsAllowed(context)) {
                NotificationIntroScreen(onDone = vm::markNotificationIntroSeen)
                return
            }
            val screen = when {
                showSettings -> Screen.Settings
                showInbox -> Screen.Inbox
                else -> Screen.Chat
            }
            val toChat = {
                showSettings = false
                showInbox = false
            }
            // The chat leaves composition while another screen covers it, and is only underneath
            // while one slides or is dragged away.
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
            // Shows itself only right after an update to a minor or major version.
            UpdateNotesSheet(vm)
        }
    }
}
