package dev.chatter.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatter.app.ui.AppRoot
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.chat.LinkGuard
import dev.chatter.app.ui.chat.LocalPosts
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.ui.theme.ChatterTheme
import dev.chatter.app.util.EXTRA_CHANNEL
import dev.chatter.app.util.EXTRA_INBOX_TAB
import dev.chatter.app.util.EXTRA_LIVE_CHANNEL
import dev.chatter.app.util.EXTRA_PROFILING_ANIMATED_EMOTES
import dev.chatter.app.util.EXTRA_PROFILING_CHANNEL
import dev.chatter.app.util.EXTRA_PROFILING_DARK_THEME
import dev.chatter.app.util.EXTRA_PROFILING_KEEP_SCREEN_ON
import dev.chatter.app.util.EXTRA_PROFILING_PURE_BLACK
import dev.chatter.app.util.EXTRA_PROFILING_SMOOTH_SCROLLING

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel((application as ChatterApp).container, createSavedStateHandle()) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            ChatterTheme(settings.themeMode, settings.dynamicColor, settings.pureBlack) {
                LinkGuard { CompositionLocalProvider(LocalPosts provides vm.posts) { AppRoot(vm) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.setUiVisible(true)
    }

    override fun onStop() {
        vm.setUiVisible(false)
        super.onStop()
    }

    /** Handles a channel from a mention or live notification, or the inbox from a shortcut. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CHANNEL)?.let { vm.requestedChannel.value = it }
        intent.getStringExtra(EXTRA_LIVE_CHANNEL)?.let(vm::openLiveChannel)
        intent.getIntExtra(EXTRA_INBOX_TAB, -1).takeIf { it >= 0 }?.let { vm.requestedInbox.value = it }
        // Any app can start this activity and must not add channels (a live notification only adds
        // the channel it was posted for), so only profiling builds listen.
        if (BuildConfig.PROFILING) applyProfilingExtras(intent)
    }

    /** How the benchmarks set the app up from outside. */
    private fun applyProfilingExtras(intent: Intent) {
        intent.booleanExtraOrNull(EXTRA_PROFILING_DARK_THEME)?.let { dark ->
            vm.setThemeMode(if (dark) ThemeMode.Dark else ThemeMode.Light)
        }
        intent.booleanExtraOrNull(EXTRA_PROFILING_PURE_BLACK)?.let(vm::setPureBlack)
        intent.booleanExtraOrNull(EXTRA_PROFILING_ANIMATED_EMOTES)?.let(vm::setAnimatedEmotes)
        intent.booleanExtraOrNull(EXTRA_PROFILING_SMOOTH_SCROLLING)?.let(vm::setSmoothScrolling)
        intent.booleanExtraOrNull(EXTRA_PROFILING_KEEP_SCREEN_ON)?.let(vm::setKeepScreenOn)
        intent.getStringExtra(EXTRA_PROFILING_CHANNEL)?.let(vm::readAsGuest)
    }

    private fun Intent.booleanExtraOrNull(name: String): Boolean? =
        if (hasExtra(name)) getBooleanExtra(name, false) else null
}
