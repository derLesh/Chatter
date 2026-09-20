package dev.chatter.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.chatter.app.ChatterApp
import dev.chatter.app.util.EXTRA_CHANNEL
import kotlinx.coroutines.launch

/**
 * Sends what the user typed into the reply action of a mention notification.
 *
 * The broadcast may be what starts the process in the first place, so sending waits for the chat
 * connection instead of giving up on the first try (see AppContainer.sendFromNotification).
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MentionNotifier.KEY_REPLY)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val container = (context.applicationContext as? ChatterApp)?.container ?: return

        // Connecting and sending outlive onReceive, so the broadcast is kept alive until it is done.
        val pending = goAsync()
        container.scope.launch {
            try {
                if (container.sendFromNotification(channel, text)) container.notifier.showSent(channel, text)
                else container.notifier.showSendFailed(channel)
            } finally {
                pending.finish()
            }
        }
    }
}
