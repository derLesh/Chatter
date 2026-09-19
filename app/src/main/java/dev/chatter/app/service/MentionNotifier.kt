package dev.chatter.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dev.chatter.app.MainActivity
import dev.chatter.app.R
import dev.chatter.app.chat.ChatItem

/** Posts one grouped notification per channel for messages that mention the user. */
class MentionNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val recent = HashMap<String, ArrayDeque<ChatItem>>()

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MENTIONS, context.getString(R.string.notif_channel_mentions), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    @Synchronized
    fun notify(item: ChatItem) {
        if (!manager.areNotificationsEnabled()) return
        val lines = recent.getOrPut(item.channel) { ArrayDeque() }
        lines.addLast(item)
        while (lines.size > 6) lines.removeFirst()

        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("#${item.channel}")
            .setGroupConversation(true)
        lines.forEach { m ->
            style.addMessage(m.text, m.timestamp, Person.Builder().setName(m.displayName ?: m.login ?: "?").build())
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_MENTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(openChannelIntent(context, item.channel))
            .setDeleteIntent(null)
            .build()
        try {
            manager.notify(item.channel.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission was revoked in the meantime.
        }
    }

    /** Removes the notification of a channel once the user looks at it. */
    @Synchronized
    fun clear(channel: String) {
        recent.remove(channel)
        manager.cancel(channel.hashCode())
    }

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_MENTIONS = "mentions"
        private const val GROUP = "mentions"
        const val EXTRA_CHANNEL = "channel"

        fun openChannelIntent(context: Context, channel: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CHANNEL, channel)
            return PendingIntent.getActivity(
                context, channel?.hashCode() ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
