package dev.chatter.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.chatter.app.ChatterApp
import dev.chatter.app.util.EXTRA_ACCOUNT
import dev.chatter.app.util.EXTRA_CHANNEL
import dev.chatter.app.util.EXTRA_WHISPER
import dev.chatter.app.util.EXTRA_WHISPER_USER_ID
import kotlinx.coroutines.launch

/**
 * Sends what was typed into a notification's reply action: to the mention's channel, or back to the
 * person who whispered.
 *
 * The broadcast may start the process, so sending waits for login and connection (see
 * AppContainer).
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ChatNotifier.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val container = (context.applicationContext as? ChatterApp)?.container ?: return
        val whisperTo = intent.getStringExtra(EXTRA_WHISPER)
        val channel = intent.getStringExtra(EXTRA_CHANNEL)
        if (whisperTo == null && channel == null) return
        // The account the notification was for. Actions from older versions have none and fail
        // instead of guessing.
        val account = intent.getStringExtra(EXTRA_ACCOUNT)

        // Connecting and sending outlast onReceive.
        val pending = goAsync()
        container.scope.launch {
            try {
                if (whisperTo != null) {
                    val userId = intent.getStringExtra(EXTRA_WHISPER_USER_ID)
                    val result = container.whisperFromNotification(account, whisperTo, userId, text)
                    // Twitch refuses whispers for various reasons; its message is the only
                    // explanation.
                    if (result.sent) container.notifier.showWhisperSent(whisperTo, text)
                    else container.notifier.showWhisperFailed(whisperTo, result.message)
                } else if (channel != null) {
                    if (container.sendFromNotification(account, channel, text)) container.notifier.showSent(channel, text)
                    else container.notifier.showSendFailed(channel)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
