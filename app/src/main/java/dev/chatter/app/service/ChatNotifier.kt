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
import dev.chatter.app.chat.InboxWhisper
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.net.HelixApi
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.bubble.BubbleActivity
import dev.chatter.app.util.ChannelIcons
import dev.chatter.app.util.EXTRA_CHANNEL
import dev.chatter.app.util.EXTRA_INBOX_TAB
import dev.chatter.app.util.EXTRA_WHISPER
import dev.chatter.app.util.EXTRA_WHISPER_USER_ID
import dev.chatter.app.util.INBOX_TAB_WHISPERS
import dev.chatter.app.util.appLaunchIntent
import dev.chatter.app.util.channelShortcut
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything Chatter tells the user about while they are not looking: one grouped notification
 * per channel for the messages that mention them, and one per person who whispers them.
 *
 * A mention notification is a conversation: it carries a long-lived shortcut for its channel,
 * which is what lets Android show it in the conversation section and — if the user turned
 * bubbles on — float it over other apps as a chat bubble. A whisper deliberately carries no
 * such shortcut: there is no whisper screen for a bubble to open, and the senders are strangers
 * who would otherwise pile up in the people space and push the channels out of it.
 */
class ChatNotifier(
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
    /** The whisper conversations currently on screen, by lowercase login. */
    private val whisperThreads = HashMap<String, WhisperThread>()

    fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_MENTIONS, context.getString(R.string.notif_channel_mentions))
        )
        // Whispers come from anyone, so they share one channel rather than getting one each.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WHISPERS, context.getString(R.string.notif_channel_whispers), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROBLEMS, context.getString(R.string.notif_channel_problems), NotificationManager.IMPORTANCE_DEFAULT)
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

    /**
     * Says that Chatter has stopped listening, and why.
     *
     * The silence it explains is the whole point of the app: when the background connection
     * cannot be started, or the Twitch login has run out, nothing else would ever say so — the
     * app is not on screen, and everything simply stays quiet until somebody opens it and
     * wonders where the mentions went.
     */
    fun notifyNotListening(reason: Int) {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_not_listening))
            .setContentText(context.getString(reason))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(reason)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openChannelIntent(context, null))
            .build()
        try {
            manager.notify(NOT_LISTENING_ID, notification)
        } catch (e: SecurityException) {
            // Permission was revoked in the meantime.
        }
    }

    /** Takes that notice down again, for when the connection is back. */
    fun clearNotListening() = manager.cancel(NOT_LISTENING_ID)

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

    // ---- Whispers --------------------------------------------------------------------------

    /** One line of a whisper conversation as the notification shows it. */
    private class WhisperLine(val text: String, val timestamp: Long, val own: Boolean)

    /** What is known about the person on the other side, kept so an answer can go back to them. */
    private class WhisperThread(var name: String, var userId: String?) {
        val lines = ArrayDeque<WhisperLine>()
    }

    /** Posts, or adds to, the conversation with whoever whispered. */
    suspend fun notifyWhisper(whisper: InboxWhisper) {
        if (!manager.areNotificationsEnabled()) return
        // Fills the cache for this sender; the older lines use what is already in it.
        loadSenderIcon(whisper.login)
        addWhisperLine(
            login = whisper.login,
            name = whisper.displayName,
            userId = whisper.userId,
            line = WhisperLine(whisper.text, whisper.timestamp, own = false),
        )
    }

    /** Shows an answer the user sent straight from the notification in that same conversation. */
    fun showWhisperSent(login: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        addWhisperLine(login, name = null, userId = null, line = WhisperLine(text, System.currentTimeMillis(), own = true))
    }

    /**
     * Says in the conversation itself why an answer did not go out. Twitch refuses whispers for
     * reasons the app cannot see coming, and by then the keyboard the user typed on is long gone.
     */
    fun showWhisperFailed(login: String, reason: String) {
        if (!manager.areNotificationsEnabled()) return
        addWhisperLine(login, name = null, userId = null, line = WhisperLine(reason, System.currentTimeMillis(), own = true))
    }

    /** Takes the whisper notifications down once the user opens the tab that holds them. */
    @Synchronized
    fun clearWhispers() {
        whisperThreads.keys.forEach { manager.cancel(it, WHISPER_NOTIFICATION_ID) }
        whisperThreads.clear()
    }

    @Synchronized
    private fun addWhisperLine(login: String, name: String?, userId: String?, line: WhisperLine) {
        val key = login.lowercase()
        val thread = whisperThreads.getOrPut(key) { WhisperThread(name ?: login, userId) }
        name?.let { thread.name = it }
        userId?.let { thread.userId = it }
        thread.lines.addLast(line)
        while (thread.lines.size > 6) thread.lines.removeFirst()

        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val sender = Person.Builder().setName(thread.name).setKey(key).setIcon(senderIcons[key]).build()
        val style = NotificationCompat.MessagingStyle(me)
        // A null person is what MessagingStyle reads as "the user themselves".
        thread.lines.forEach { style.addMessage(it.text, it.timestamp, if (it.own) null else sender) }

        val notification = NotificationCompat.Builder(context, CHANNEL_WHISPERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_WHISPERS)
            .setAutoCancel(true)
            .setContentIntent(openWhispersIntent())
            .addAction(whisperReplyAction(login, thread.userId))
            .build()
        try {
            // The login is the tag, so one notification per person and none of them collide with
            // the mentions, which carry no tag at all.
            manager.notify(key, WHISPER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission was revoked in the meantime.
        }
    }

    private fun openWhispersIntent(): PendingIntent {
        val intent = appLaunchIntent(context, channel = null).putExtra(EXTRA_INBOX_TAB, INBOX_TAB_WHISPERS)
        return PendingIntent.getActivity(
            context, WHISPER_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun whisperReplyAction(login: String, userId: String?): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java)
            .putExtra(EXTRA_WHISPER, login)
            .putExtra(EXTRA_WHISPER_USER_ID, userId)
        val pending = PendingIntent.getBroadcast(
            context, "whisper:$login".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val label = context.getString(R.string.notif_reply)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, pending)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    // ------------------------------------------------------------------------------------------

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
        const val CHANNEL_WHISPERS = "whispers"
        private const val GROUP_WHISPERS = "whispers"
        /** Whisper notifications are told apart by the sender's login as their tag, not by id. */
        private const val WHISPER_NOTIFICATION_ID = 2
        const val CHANNEL_PROBLEMS = "problems"
        private const val NOT_LISTENING_ID = 3

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
