package dev.chatter.app.ui.bubble

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatter.app.ChatterApp
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.MessageJump
import dev.chatter.app.ui.chat.LinkGuard
import dev.chatter.app.ui.chat.LocalPosts
import dev.chatter.app.ui.theme.ChatterTheme
import dev.chatter.app.util.EXTRA_CHANNEL
import dev.chatter.app.util.EXTRA_MESSAGE

/**
 * One channel's chat as an Android chat bubble. A separate activity with its own [MainViewModel],
 * since the bubble has its own draft, reply and scroll position, and closing it must not affect the
 * main window.
 */
class BubbleActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel((application as ChatterApp).container, createSavedStateHandle()) } }
    }

    private val channel: String? get() = intent?.getStringExtra(EXTRA_CHANNEL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.inBubble = true
        // Not again when recreated, or every rotation would scroll back to the mention.
        if (savedInstanceState == null) requestJump(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            ChatterTheme(settings.themeMode, settings.dynamicColor, settings.pureBlack) {
                LinkGuard { CompositionLocalProvider(LocalPosts provides vm.posts) { BubbleScreen(vm, channel) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestJump(intent)
    }

    private fun requestJump(intent: Intent?) {
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: return
        channel?.let { vm.requestedMessage.value = MessageJump(it, message) }
    }

    override fun onStart() {
        super.onStart()
        // While open, the bubble's channel counts as read: its mentions stop accumulating and its
        // notification goes away.
        channel?.let { vm.selectChannel(it) }
        vm.setUiVisible(true)
    }

    override fun onStop() {
        vm.setUiVisible(false)
        super.onStop()
    }
}
