package me.lesh.chatter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.lesh.chatter.R
import me.lesh.chatter.auth.SevenTvLogin

/**
 * The 7TV login: first the sheet asking for it when an emote's add button needs it, then 7TV's
 * login page over everything else. 7TV logs in with Twitch, so the page is Twitch's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SevenTvLoginHost(vm: MainViewModel) {
    val asking by vm.askSevenTvLogin.collectAsStateWithLifecycle()
    asking?.let { emote ->
        FormSheet(
            title = stringResource(R.string.seventv_login_title),
            text = stringResource(R.string.seventv_login_text),
            confirmLabel = stringResource(R.string.seventv_login_confirm),
            onConfirm = { vm.openSevenTvLogin(emote) },
            onDismiss = vm::dismissSevenTvLoginQuestion,
        )
    }
    val page by vm.sevenTvLogin.collectAsStateWithLifecycle()
    val login = page?.start ?: return
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        onBackCompleted = vm::closeSevenTvLogin,
    )
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column {
            TopAppBar(
                title = { Text(stringResource(R.string.seventv_login_title)) },
                navigationIcon = {
                    IconButton(onClick = vm::closeSevenTvLogin) {
                        Icon(Icons.Default.Close, stringResource(R.string.seventv_login_close))
                    }
                },
            )
            LoginWebView(
                url = login.authorizeUrl,
                modifier = Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
                isRedirect = SevenTvLogin::isCallback,
                onRedirect = vm::finishSevenTvLogin,
            )
        }
    }
}
