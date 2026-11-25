package me.lesh.chatter.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.ui.changelog.UpdateNotesSheet
import me.lesh.chatter.ui.channels.ChannelScreen
import me.lesh.chatter.ui.inbox.InboxScreen
import me.lesh.chatter.ui.onboarding.OnboardingScreen
import me.lesh.chatter.ui.settings.CrashOfferSheet

/** The screens after login: the chat and those it opens over itself, deepest last for the slide's direction. */
private enum class Screen { Chat, Channel, Inbox, Picture, Settings }

@Composable
fun AppRoot(vm: MainViewModel) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    // Saved, so turning the phone does not drop the user back into the chat.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showInbox by rememberSaveable { mutableStateOf(false) }
    // The channel or combined chat whose page is open over the chat.
    var channelPage by rememberSaveable { mutableStateOf<String?>(null) }

    // Requested from outside (shortcut, whisper notification). The inbox clears it after scrolling
    // to the tab. A guest has no mentions or whispers, so no inbox either.
    val requestedInbox by vm.requestedInbox.collectAsStateWithLifecycle()
    LaunchedEffect(requestedInbox, auth) {
        if (requestedInbox == null) return@LaunchedEffect
        if (auth !is AuthState.LoggedIn) {
            vm.requestedInbox.value = null
            return@LaunchedEffect
        }
        showSettings = false
        showInbox = true
    }
    // After the last logout the settings would stay underneath the login screen, and the next login
    // would land there instead of in the chat.
    LaunchedEffect(auth) {
        if (auth is AuthState.LoggedOut) {
            showSettings = false
            channelPage = null
        }
        if (auth !is AuthState.LoggedIn) showInbox = false
    }
    // The send screen's way to its uploader settings; back from there returns to the picture.
    val requestedSettings by vm.requestedSettingsPage.collectAsStateWithLifecycle()
    LaunchedEffect(requestedSettings) { if (requestedSettings != null) showSettings = true }
    val pictureOpened by vm.pictureOpened.collectAsStateWithLifecycle()
    LaunchedEffect(pictureOpened) {
        if (!pictureOpened) return@LaunchedEffect
        showSettings = false
        vm.pictureOpened.value = false
    }
    when (auth) {
        AuthState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        AuthState.LoggedOut -> LoginScreen(vm)
        is AuthState.LoggedIn, AuthState.Guest -> {
            // Blank while unknown, so neither the onboarding nor the chat flashes up for nothing.
            when (vm.onboardingSeen.collectAsStateWithLifecycle().value) {
                null -> {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    return
                }
                false -> {
                    OnboardingScreen(vm)
                    return
                }
                true -> Unit
            }
            // Once, right after the first login; guests get no notifications to allow.
            val introSeen by vm.notificationIntroSeen.collectAsStateWithLifecycle()
            val context = LocalContext.current
            if (auth is AuthState.LoggedIn && introSeen == false && !notificationsAllowed(context)) {
                NotificationIntroScreen(onDone = vm::markNotificationIntroSeen)
                return
            }
            // A picture shared in shows over the chat and the inbox, below the settings it opens.
            val picture = vm.picture.isOpen
            val screen = when {
                showSettings -> Screen.Settings
                picture -> Screen.Picture
                showInbox -> Screen.Inbox
                channelPage != null -> Screen.Channel
                else -> Screen.Chat
            }
            val toChat = {
                showSettings = false
                showInbox = false
                channelPage = null
                vm.closeWhisper()
            }
            val back: () -> Unit = when (screen) {
                Screen.Settings -> if (picture) ({ showSettings = false }) else toChat
                Screen.Picture -> ({ if (vm.picture.isBusy) vm.picture.cancel() else vm.picture.close() })
                else -> toChat
            }
            // The chat leaves composition while another screen covers it, and is only underneath
            // while one slides or is dragged away.
            rememberPredictiveTransition(
                current = screen,
                backTo = when {
                    screen == Screen.Chat -> null
                    screen == Screen.Settings && picture -> Screen.Picture
                    screen == Screen.Settings && showInbox -> Screen.Inbox
                    screen == Screen.Picture && showInbox -> Screen.Inbox
                    else -> Screen.Chat
                },
                onBack = back,
                label = "screen",
            ).AnimatedContent(
                transitionSpec = { slideBetweenScreens(forward = targetState.ordinal > initialState.ordinal) },
            ) { shown ->
                when (shown) {
                    Screen.Settings -> SettingsScreen(vm, onBack = back)
                    Screen.Picture -> SendPictureScreen(vm)
                    Screen.Inbox -> InboxScreen(
                        vm,
                        onOpenMention = { mention ->
                            vm.openMessage(mention.channel, mention.id)
                            showInbox = false
                        },
                        onBack = toChat,
                    )
                    Screen.Channel -> channelPage?.let { page ->
                        ChannelScreen(
                            vm,
                            page = page,
                            onOpenPage = { channelPage = it },
                            onJump = { posted ->
                                vm.openMessage(page, posted.messageId)
                                channelPage = null
                            },
                            onBack = { channelPage = null },
                        )
                    }
                    Screen.Chat -> MainScreen(
                        vm,
                        onInbox = { showInbox = true },
                        onSettings = { showSettings = true },
                        onOpenChannel = { channelPage = it },
                    )
                }
            }
            // Shows itself only right after an update to a minor or major version.
            UpdateNotesSheet(vm)
            CrashOfferSheet(vm)
            SevenTvLoginHost(vm)
        }
    }
}
