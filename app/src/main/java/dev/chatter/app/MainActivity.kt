package dev.chatter.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatter.app.ui.AppRoot
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.theme.ChatterTheme
import dev.chatter.app.util.EXTRA_CHANNEL
import dev.chatter.app.util.EXTRA_INBOX_TAB
import dev.chatter.app.util.EXTRA_PROFILING_CHANNEL
import dev.chatter.app.util.EXTRA_PROFILING_TOKEN

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel((application as ChatterApp).container) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            ChatterTheme(settings.themeMode, settings.dynamicColor, settings.pureBlack) { AppRoot(vm) }
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

    /** What brought the app up: a mention notification names its channel, a shortcut the inbox. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CHANNEL)?.let { vm.requestedChannel.value = it }
        intent.getIntExtra(EXTRA_INBOX_TAB, -1).takeIf { it >= 0 }?.let { vm.requestedInbox.value = it }
        // An exported activity that logs in with whatever token it is handed would let any app on
        // the phone swap the account underneath the user, so no build anybody installs has this.
        if (BuildConfig.PROFILING) {
            intent.getStringExtra(EXTRA_PROFILING_TOKEN)?.let { token ->
                vm.logInForProfiling(token, intent.getStringExtra(EXTRA_PROFILING_CHANNEL))
            }
        }
    }
}
