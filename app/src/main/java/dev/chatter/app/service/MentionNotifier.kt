package dev.chatter.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import dev.chatter.app.MainActivity
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.bubble.BubbleActivity
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Posts one grouped notification per channel for messages that mention the user.
 *
 * Every notification is a conversation: it carries a long-lived shortcut for its channel, which
 * is what lets Android show it in the conversation section and — if the user turned bubbles on —
 * float it over other apps as a chat bubble.
 */
class MentionNotifier(
    private val context: Context,
    private val channels: ChannelRepository,
    private val settings: StateFlow<Settings>,
    private val imageLoader: ImageLoader,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val recent = HashMap<String, ArrayDeque<ChatItem>>()
    /** Channel avatars as notification icons, loaded once per channel. */
    private val icons = ConcurrentHashMap<String, IconCompat>()

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MENTIONS, context.getString(R.string.notif_channel_mentions), NotificationManager.IMPORTANCE_HIGH)
                .apply { setAllowBubbles(true) }
        )
    }

    /** Loads the channel avatar (off the main thread) and then posts the notification. */
    suspend fun notify(item: ChatItem) {
        if (!manager.areNotificationsEnabled()) return
        post(item, channelIcon(item.channel))
    }

    /** Shows a message the user sent straight from the notification in that same conversation. */
    suspend fun showSent(channel: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        post(
            ChatItem(
                id = "notification-reply-${System.nanoTime()}", channel = channel, kind = MessageKind.Chat,
                timestamp = System.currentTimeMillis(), text = text, isOwn = true,
            ),
            channelIcon(channel),
        )
    }

    /**
     * Says in the conversation itself that a reply did not go out — the keyboard is long gone by
     * then, so a message in the thread is the only place the user still looks.
     */
    suspend fun showSendFailed(channel: String) {
        if (!manager.areNotificationsEnabled()) return
        val text = context.getString(R.string.notif_reply_failed)
        post(
            ChatItem(
                id = "notification-failed-${System.nanoTime()}", channel = channel, kind = MessageKind.Notice,
                timestamp = System.currentTimeMillis(), text = text, displayName = context.getString(R.string.app_name),
            ),
            channelIcon(channel),
        )
    }

    @Synchronized
    private fun post(item: ChatItem, icon: IconCompat) {
        val lines = recent.getOrPut(item.channel) { ArrayDeque() }
        lines.addLast(item)
        while (lines.size > 6) lines.removeFirst()

        val name = channelName(item.channel)
        val shortcutId = publishShortcut(item.channel, name, icon)
        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("#${item.channel}")
            .setGroupConversation(true)
        lines.forEach { m ->
            // A null person is what MessagingStyle reads as "the user themselves".
            val from = if (m.isOwn) null else Person.Builder().setName(m.displayName ?: m.login ?: "?").build()
            style.addMessage(m.text, m.timestamp, from)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_MENTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .setContentIntent(openChannelIntent(context, item.channel))
            .addAction(replyAction(item.channel))
            .apply { if (settings.value.bubbles) setBubbleMetadata(bubbleMetadata(item.channel, icon)) }
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

    private fun channelName(channel: String): String = channels.info.value[channel]?.displayName ?: channel

    /**
     * The channel avatar, falling back to the app icon. Android needs a real icon for a bubble,
     * and an adaptive bitmap is what it crops into the round conversation shape.
     */
    private suspend fun channelIcon(channel: String): IconCompat {
        icons[channel]?.let { return it }
        val fallback = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val url = channels.info.value[channel]?.avatarUrl ?: return fallback
        // Hardware bitmaps cannot leave the process, and a notification icon does exactly that.
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val image = (runCatching { imageLoader.execute(request) }.getOrNull() as? SuccessResult)?.image
        val bitmap = (image as? BitmapImage)?.bitmap ?: return fallback
        return IconCompat.createWithAdaptiveBitmap(bitmap).also { icons[channel] = it }
    }

    /**
     * The conversation shortcut a notification points at. It has to exist before the notification
     * arrives, and stays around ("long lived") so the bubble survives the notification itself.
     */
    private fun publishShortcut(channel: String, name: String, icon: IconCompat): String {
        val id = "$SHORTCUT_PREFIX$channel"
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(name)
            .setLongLived(true)
            .setIcon(icon)
            .setCategories(setOf(SHORTCUT_CATEGORY))
            .setPerson(Person.Builder().setName(name).setKey(id).setIcon(icon).setImportant(true).build())
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA_CHANNEL, channel)
            )
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        return id
    }

    /**
     * Answering without opening the app. The intent has to be mutable — that is where Android
     * writes what was typed before handing it to [ReplyReceiver].
     */
    private fun replyAction(channel: String): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_CHANNEL, channel)
        val pending = PendingIntent.getBroadcast(
            context, channel.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val label = context.getString(R.string.notif_reply)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, pending)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun bubbleMetadata(channel: String, icon: IconCompat): NotificationCompat.BubbleMetadata {
        // The data uri makes every channel its own document, so two channels bubble side by side.
        val intent = Intent(context, BubbleActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("chatter://channel/$channel".toUri())
            .putExtra(EXTRA_CHANNEL, channel)
        val pending = PendingIntent.getActivity(
            context, channel.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.BubbleMetadata.Builder(pending, icon)
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            // The notification stays: a bubble the user has not opened yet is easy to miss.
            .setSuppressNotification(false)
            .build()
    }

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_MENTIONS = "mentions"
        private const val GROUP = "mentions"
        private const val SHORTCUT_PREFIX = "channel:"
        private const val SHORTCUT_CATEGORY = "android.shortcut.conversation"
        private const val BUBBLE_HEIGHT_DP = 620
        const val EXTRA_CHANNEL = "channel"
        /** Where Android puts the text typed into the reply action. */
        const val KEY_REPLY = "reply"

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
