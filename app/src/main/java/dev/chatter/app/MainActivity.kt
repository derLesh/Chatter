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
import dev.chatter.app.service.MentionNotifier
import dev.chatter.app.ui.AppRoot
import dev.chatter.app.ui.MainViewModel
import dev.chatter.app.ui.theme.ChatterTheme

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
            ChatterTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) { AppRoot(vm) }
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

    /** A tap on a mention notification carries the channel to open. */
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(MentionNotifier.EXTRA_CHANNEL)?.let { vm.requestedChannel.value = it }
    }
}
