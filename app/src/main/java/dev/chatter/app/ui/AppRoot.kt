package dev.chatter.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chatter.app.auth.AuthState
import dev.chatter.app.ui.changelog.UpdateNotesSheet
import dev.chatter.app.ui.inbox.InboxScreen

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
