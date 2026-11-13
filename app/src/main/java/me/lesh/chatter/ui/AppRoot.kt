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
import me.lesh.chatter.ui.inbox.InboxScreen
import me.lesh.chatter.ui.onboarding.OnboardingScreen

/** The screens after login: the chat and those it opens over itself, deepest last for the slide's direction. */
private enum class Screen { Chat, Inbox, Picture, Settings }

@Composable
fun AppRoot(vm: MainViewModel) {
    val auth by vm.authState.collectAsStateWithLifecycle()
    // Saved, so turning the phone does not drop the user back into the chat.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showInbox by rememberSaveable { mutableStateOf(false) }

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
        if (auth is AuthState.LoggedOut) showSettings = false
        if (auth !is AuthState.LoggedIn) showInbox = false
    }
    // The send screen's way to its uploader settings; back from there returns to the picture.
    val requestedSettings by vm.requestedSettingsPage.collectAsStateWithLifecycle()
    LaunchedEffect(requestedSettings) { if (requestedSettings != null) showSettings = true }
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
                else -> Screen.Chat
            }
            val toChat = {
                showSettings = false
                showInbox = false
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
                    Screen.Chat -> MainScreen(vm, onInbox = { showInbox = true }, onSettings = { showSettings = true })
                }
            }
            // Shows itself only right after an update to a minor or major version.
            UpdateNotesSheet(vm)
        }
    }
}
