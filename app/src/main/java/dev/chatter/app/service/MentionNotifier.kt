package dev.chatter.app.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import dev.chatter.app.R
import dev.chatter.app.channels.ChannelIdentity
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.net.HelixApi
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.bubble.BubbleActivity
import dev.chatter.app.util.ChannelIcons
import dev.chatter.app.util.EXTRA_CHANNEL
import dev.chatter.app.util.appLaunchIntent
import dev.chatter.app.util.channelShortcut
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
    private val helix: HelixApi,
    private val settings: StateFlow<Settings>,
    private val icons: ChannelIcons,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val recent = HashMap<String, ArrayDeque<ChatItem>>()
    /** Twitch profile pictures of the chatters in the notifications, by lowercase login. */
    private val senderIcons = ConcurrentHashMap<String, IconCompat>()

    fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_MENTIONS, context.getString(R.string.notif_channel_mentions))
        )
        // Mentions used to share one notification channel; every Twitch channel has its own now.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_MENTIONS)
    }

    /**
     * Gives every Twitch channel a notification channel of its own, so a sound, a vibration
     * pattern or plain silence can be picked per streamer in the system settings. Creating one
     * that already exists only renames it - whatever the user set there is theirs to keep.
     */
    fun syncChannels(channels: List<ChannelIdentity>) {
        channels.forEach { ensureChannel(it.login, it.name) }
        val wanted = channels.mapTo(HashSet()) { mentionChannelId(it.login) }
        // A channel the user removed would otherwise keep its row in the system settings forever.
        // Conversation channels are spared: Android makes those itself, in the same group, when
        // the user gives one chat its own sound, and they are not ours to throw away.
        nm.notificationChannels
            .filter { it.conversationId == null && it.group == GROUP_MENTIONS && it.id !in wanted }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    private fun ensureChannel(login: String, name: String) {
        nm.createNotificationChannel(
            NotificationChannel(mentionChannelId(login), name, NotificationManager.IMPORTANCE_HIGH).apply {
                group = GROUP_MENTIONS
                setAllowBubbles(true)
            }
        )
    }

    /** Loads the pictures (off the main thread) and then posts the notification. */
    suspend fun notify(item: ChatItem) {
        if (!manager.areNotificationsEnabled()) return
        val icon = icons.channel(item.channel)
        // Fills the cache for this chatter; the older lines use what is already in it.
        loadSenderIcon(item.login)
        post(item, icon)
    }

    /** Shows a message the user sent straight from the notification in that same conversation. */
    suspend fun showSent(channel: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        post(
            ChatItem(
                id = "notification-reply-${System.nanoTime()}", channel = channel, kind = MessageKind.Chat,
                timestamp = System.currentTimeMillis(), text = text, isOwn = true,
            ),
            icons.channel(channel),
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
            icons.channel(channel),
        )
    }

    @Synchronized
    private fun post(item: ChatItem, icon: IconCompat) {
        val lines = recent.getOrPut(item.channel) { ArrayDeque() }
        lines.addLast(item)
        while (lines.size > 6) lines.removeFirst()

        val name = channelName(item.channel)
        // A mention can beat the channel list to it, e.g. right after restoring a backup.
        ensureChannel(item.channel, name)
        val shortcutId = publishShortcut(item.channel, name, icon)
        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("#${item.channel}")
            .setGroupConversation(true)
        lines.forEach { m ->
            // A null person is what MessagingStyle reads as "the user themselves".
            val from = if (m.isOwn) null else Person.Builder()
                .setName(m.displayName ?: m.login ?: "?")
                .setKey(m.login)
                .setIcon(m.login?.lowercase()?.let { senderIcons[it] })
                .build()
            style.addMessage(m.text, m.timestamp, from)
        }

        val notification = NotificationCompat.Builder(context, mentionChannelId(item.channel))
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
     * The Twitch profile picture of whoever wrote the message, looked up once per chatter. Off
     * unless the user asked for it: it costs a Twitch request and a download per new name.
     */
    private suspend fun loadSenderIcon(login: String?) {
        val key = login?.lowercase() ?: return
        if (!settings.value.senderAvatars || senderIcons.containsKey(key)) return
        val url = runCatching { helix.users(listOf(key)).firstOrNull()?.profileImageUrl }.getOrNull() ?: return
        icons.load(url)?.let {
            // Mentions come from ever new people; the cache must not grow without end.
            if (senderIcons.size >= MAX_SENDER_ICONS) senderIcons.clear()
            senderIcons[key] = it
        }
    }

    /**
     * The conversation shortcut a notification points at. The channel list publishes the same
     * shortcut (see [dev.chatter.app.util.ChannelShortcuts]), but a notification cannot wait for
     * that: the shortcut has to exist before the notification naming it arrives.
     */
    private fun publishShortcut(channel: String, name: String, icon: IconCompat): String {
        val shortcut = channelShortcut(context, channel, name, icon)
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        return shortcut.id
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
        /** Holds the per-channel mention channels together in the system settings. */
        private const val GROUP_MENTIONS = "mentions"
        /** The one shared mentions channel of older versions, replaced by one per channel. */
        private const val LEGACY_CHANNEL_MENTIONS = "mentions"
        private const val GROUP = "mentions"

        /** The notification channel mentions in [channel] are posted to. */
        fun mentionChannelId(channel: String): String = "mentions:$channel"
        private const val BUBBLE_HEIGHT_DP = 620
        private const val MAX_SENDER_ICONS = 100
        /** Where Android puts the text typed into the reply action. */
        const val KEY_REPLY = "reply"

        fun openChannelIntent(context: Context, channel: String?): PendingIntent {
            val intent = appLaunchIntent(context, channel)
            return PendingIntent.getActivity(
                context, channel?.hashCode() ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
