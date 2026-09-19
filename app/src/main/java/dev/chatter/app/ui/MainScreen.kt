package dev.chatter.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.service.ChatService
import dev.chatter.app.ui.channels.AddChannelDialog
import dev.chatter.app.ui.channels.ChannelTopBar
import dev.chatter.app.ui.chat.ChatList
import dev.chatter.app.ui.chat.ChatStyle
import dev.chatter.app.ui.chat.EmotePickerSheet
import dev.chatter.app.ui.chat.InputBar
import dev.chatter.app.ui.chat.MessageActionsSheet
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun MainScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val unread by vm.unreadMentions.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val active by vm.activeChannel.collectAsStateWithLifecycle()
    val emoteVersion by vm.emoteVersion.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pagerState = rememberPagerState { channels.size }

    var actionItem by remember { mutableStateOf<ChatItem?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    val loader = if (settings.animatedEmotes) vm.imageLoader else vm.staticImageLoader
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val style = remember(settings.fontSize, settings.showTimestamps, dark, colors) {
        ChatStyle(
            fontSize = settings.fontSize,
            showTimestamps = settings.showTimestamps,
            dark = dark,
            secondaryText = colors.onSurfaceVariant,
            linkColor = colors.secondary,
            mentionBackground = Color(0x33EB0400),
            noticeBackground = colors.primary.copy(alpha = 0.12f),
            accent = colors.secondary,
        )
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
                connection = connection,
                imageLoader = vm.imageLoader,
                onSelect = { ch -> scope.launch { pagerState.scrollToPage(channels.indexOf(ch).coerceAtLeast(0)) } },
                onAdd = { showAdd = true },
                onRemove = vm::removeChannel,
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
        MessageActionsSheet(
            item = item,
            onReply = { vm.startReply(item) },
            onMention = { vm.mention(item) },
            onDismiss = { actionItem = null },
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
