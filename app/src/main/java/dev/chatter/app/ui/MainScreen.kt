package dev.chatter.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
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
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.Segment
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.service.ChatService
import dev.chatter.app.ui.channels.AddChannelDialog
import dev.chatter.app.ui.channels.RenameChannelDialog
import dev.chatter.app.ui.channels.ChannelTopBar
import dev.chatter.app.ui.chat.ChatList
import dev.chatter.app.ui.chat.ChatStyle
import dev.chatter.app.ui.chat.EmoteCardSheet
import dev.chatter.app.ui.chat.EmotePickerSheet
import dev.chatter.app.ui.chat.InputBar
import dev.chatter.app.ui.chat.UserCardSheet
import dev.chatter.app.ui.theme.highlightBackground
import dev.chatter.app.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun MainScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val unread by vm.unreadMentions.collectAsStateWithLifecycle()
    val unreadMessages by vm.unreadMessages.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val active by vm.activeChannel.collectAsStateWithLifecycle()
    val customNames by vm.customNames.collectAsStateWithLifecycle()
    val hiddenUnread by vm.hiddenUnread.collectAsStateWithLifecycle()
    val emoteVersion by vm.emoteVersion.collectAsStateWithLifecycle()
    val modChannels by vm.modChannels.collectAsStateWithLifecycle()
    val roomStates by vm.roomStates.collectAsStateWithLifecycle()
    val roles by vm.roles.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Opening the settings takes this screen out of the composition, so the pager starts over.
    // Anchoring it to the channel the user was last on keeps them there when they come back.
    val pagerState = rememberPagerState(initialPage = channels.indexOf(active).coerceAtLeast(0)) { channels.size }

    var actionItem by remember { mutableStateOf<ChatItem?>(null) }
    var emoteCard by remember { mutableStateOf<Segment.EmoteSeg?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }

    val loader = if (settings.animatedEmotes) vm.imageLoader else vm.staticImageLoader
    val dark = isAppInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val style = remember(settings.fontSize, settings.timestamps, settings.highlightColor, settings.alternateBackground, settings.showDeleted, settings.nameColors, dark, colors) {
        ChatStyle(
            fontSize = settings.fontSize,
            timestamps = settings.timestamps,
            dark = dark,
            secondaryText = colors.onSurfaceVariant,
            linkColor = colors.primary,
            mentionBackground = highlightBackground(settings.highlightColor, colors),
            alternateBackground = if (settings.alternateBackground) colors.onSurface.copy(alpha = 0.05f) else null,
            noticeBackground = colors.primaryContainer.copy(alpha = 0.35f),
            accent = colors.primary,
            showDeleted = settings.showDeleted,
            nameColors = settings.nameColors,
        )
    }

    // Only while the chat is on screen; leaving it (or the settings) lets the screen sleep again.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Keep the chat service (and with it the connection) running while there are channels.
    LifecycleStartEffect(channels.isNotEmpty()) {
        if (channels.isNotEmpty()) ChatService.start(context)
        onStopOrDispose { }
    }

    // Poll live status only while the app is on screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLiveStatus() }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }

    // The page on screen defines the active channel.
    LaunchedEffect(pagerState, channels) {
        snapshotFlow { pagerState.currentPage }.collect { vm.selectChannel(channels.getOrNull(it)) }
    }
    // Jump to a channel requested by a notification tap or right after adding it.
    LaunchedEffect(channels) {
        vm.requestedChannel.filterNotNull().collect { ch ->
            val index = channels.indexOf(ch)
            if (index >= 0) {
                pagerState.scrollToPage(index)
                vm.requestedChannel.value = null
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ChannelTopBar(
                channels = channels,
                active = active,
                info = info,
                unread = unread,
                unreadMessages = unreadMessages,
                roomState = active?.let { roomStates[it] },
                roleBadge = active?.let { ch -> roles[ch]?.let { vm.roleBadge(ch, it) } },
                connection = connection,
                showUnread = settings.unreadInTitleBar,
                hiddenUnread = hiddenUnread,
                imageLoader = vm.imageLoader,
                onSelect = { ch -> scope.launch { pagerState.scrollToPage(channels.indexOf(ch).coerceAtLeast(0)) } },
                onAdd = { showAdd = true },
                onRemove = vm::removeChannel,
                onRename = { renameTarget = it },
                onMove = vm::moveChannel,
                onSettings = onSettings,
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
            if (channels.isEmpty()) {
                EmptyState(onAdd = { showAdd = true }, modifier = Modifier.weight(1f))
            } else {
                HorizontalPager(
                    state = pagerState,
                    key = { channels.getOrElse(it) { "" } },
                    modifier = Modifier.weight(1f),
                ) { page ->
                    val channel = channels.getOrNull(page) ?: return@HorizontalPager
                    ChatList(
                        messages = remember(channel) { vm.chat(channel) },
                        style = style,
                        imageLoader = loader,
                        onAction = { actionItem = it },
                        modifier = Modifier.fillMaxSize(),
                        smoothScrolling = settings.smoothScrolling,
                        onEmoteClick = { emoteCard = it },
                    )
                }
            }
            InputBar(
                value = vm.input,
                onValueChange = vm::onInputChange,
                enabled = active != null && connection == ConnectionState.Connected,
                replyTo = vm.replyTo,
                suggestions = vm.suggestions,
                imageLoader = loader,
                onSuggestion = vm::applySuggestion,
                onCancelReply = vm::cancelReply,
                onEmotePicker = { showPicker = true },
                onSend = vm::send,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    actionItem?.let { item ->
        UserCardSheet(
            item = item,
            canModerate = item.channel in modChannels,
            style = style,
            imageLoader = loader,
            load = { vm.loadUserCard(item) },
            onReply = { vm.startReply(item) },
            onMention = { vm.mention(item) },
            onDelete = { vm.deleteMessage(item) },
            onTimeout = { vm.timeoutUser(item) },
            onBan = { vm.banUser(item) },
            onDismiss = { actionItem = null },
        )
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
        val emotes = remember(active, emoteVersion) { vm.emotesFor(active) }
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

@Composable
fun AppRoot(vm: MainViewModel) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    when (auth) {
        dev.chatter.app.auth.AuthState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        dev.chatter.app.auth.AuthState.LoggedOut -> LoginScreen(vm)
        is dev.chatter.app.auth.AuthState.LoggedIn ->
            if (showSettings) SettingsScreen(vm, onBack = { showSettings = false })
            else MainScreen(vm, onSettings = { showSettings = true })
    }
}
