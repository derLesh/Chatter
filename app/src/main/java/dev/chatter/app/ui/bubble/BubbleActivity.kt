package dev.chatter.app.ui.bubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatter.app.ChatterApp
import dev.chatter.app.service.MentionNotifier
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.theme.ChatterTheme

/**
 * One channel's chat, floating over whatever the user is doing, as an Android chat bubble.
 *
 * It is its own activity (and its own [MainViewModel]) on purpose: the bubble has a draft, a
 * reply and a scroll position of its own, and closing it must not disturb the main window.
 */
class BubbleActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel((application as ChatterApp).container) } }
    }

    private val channel: String? get() = intent?.getStringExtra(MentionNotifier.EXTRA_CHANNEL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.inBubble = true
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            ChatterTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                BubbleScreen(vm, channel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The bubble is a chat window like any other: while it is open the channel is the one
        // being read, so its mentions stop piling up and its notification goes away.
        channel?.let { vm.selectChannel(it) }
        vm.setUiVisible(true)
    }

    override fun onStop() {
        vm.setUiVisible(false)
        super.onStop()
    }
}
