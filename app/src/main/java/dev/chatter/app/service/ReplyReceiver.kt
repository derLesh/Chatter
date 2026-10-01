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
 * Sends what the user typed into the reply action of a notification — into the channel a mention
 * came from, or back to whoever whispered.
 *
 * The broadcast may be what starts the process in the first place, so sending waits for the login
 * and the chat connection instead of giving up on the first try (see AppContainer).
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
        // The account the notification was for; an action from before replies knew it has none,
        // and fails rather than guess.
        val account = intent.getStringExtra(EXTRA_ACCOUNT)

        // Connecting and sending outlive onReceive, so the broadcast is kept alive until it is done.
        val pending = goAsync()
        container.scope.launch {
            try {
                if (whisperTo != null) {
                    val userId = intent.getStringExtra(EXTRA_WHISPER_USER_ID)
                    val result = container.whisperFromNotification(account, whisperTo, userId, text)
                    // Twitch refuses whispers for reasons of its own, and its wording is the only
                    // thing that explains which one it was.
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
