package dev.chatter.app.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
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
import dev.chatter.app.net.HelixStream
import dev.chatter.app.settings.Settings
import dev.chatter.app.ui.bubble.BubbleActivity
import dev.chatter.app.util.ChannelIcons
import dev.chatter.app.util.EXTRA_ACCOUNT
import dev.chatter.app.util.EXTRA_CHANNEL
import dev.chatter.app.util.EXTRA_INBOX_TAB
import dev.chatter.app.util.EXTRA_LIVE_CHANNEL
import dev.chatter.app.util.EXTRA_MESSAGE
import dev.chatter.app.util.EXTRA_WHISPER
import dev.chatter.app.util.EXTRA_WHISPER_USER_ID
import dev.chatter.app.util.INBOX_TAB_WHISPERS
import dev.chatter.app.util.appLaunchIntent
import dev.chatter.app.util.channelShortcut
import dev.chatter.app.util.channelShortcutId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Notifications: one per channel for mentions, one per person for whispers.
 *
 * A mention notification is a conversation with a long-lived shortcut for its channel, so Android
 * shows it in the conversation section and can float it as a bubble. Whispers get no shortcut:
 * there is no whisper screen for a bubble, and the senders would crowd the channels out of the
 * people space.
 */
class ChatNotifier(
    private val context: Context,
    private val channels: ChannelRepository,
    private val helix: HelixApi,
    private val settings: StateFlow<Settings>,
    private val icons: ChannelIcons,
    /** True while saving data; see [dev.chatter.app.net.DataSaving]. */
    private val saveData: StateFlow<Boolean>,
    /**
     * The active account's user id, read from the login itself rather than a flow derived from it,
     * which lags behind an account switch.
     */
    private val activeAccount: () -> String?,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val recent = HashMap<String, ArrayDeque<ChatItem>>()
    /** Twitch avatars of chatters in the notifications, by lowercase login. */
    private val senderIcons = ConcurrentHashMap<String, IconCompat>()

    /**
     * The name and picture each channel's conversation was last set up with; setting it up again
     * is two calls into the system for nothing.
     */
    private val conversations = HashMap<String, Pair<String, IconCompat>>()
    /** The newest mention in each channel's notification, by id; a tap scrolls to it. */
    private val jumpTargets = HashMap<String, String>()
    private val whisperThreads = WhisperConversations(activeAccount)

    fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_MENTIONS, context.getString(R.string.notif_channel_mentions))
        )
        // Whispers come from anyone, so they share one channel.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WHISPERS, context.getString(R.string.notif_channel_whispers), NotificationManager.IMPORTANCE_HIGH)
        )
        // Its own channel, so going live can be silenced without silencing mentions.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, context.getString(R.string.notif_channel_live), NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROBLEMS, context.getString(R.string.notif_channel_problems), NotificationManager.IMPORTANCE_DEFAULT)
        )
        // The single mentions channel of older versions.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_MENTIONS)
    }

    /**
     * One notification channel per Twitch channel, so sound and vibration can be set per streamer.
     * Creating an existing channel only renames it and keeps the user's settings.
     */
    fun syncChannels(channels: List<ChannelIdentity>) {
        channels.forEach { ensureChannel(it.login, it.name) }
        val wanted = channels.mapTo(HashSet()) { mentionChannelId(it.login) }
        // Removes channels of streamers no longer in the list. Conversation channels are kept:
        // Android creates those in the same group when the user customises a single chat.
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
     * Tells the user that Chatter stopped listening, and why: the background connection could not
     * start, or the login expired. Otherwise mentions would just stop without any sign.
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
            // Permission revoked in the meantime.
        }
    }

    /** [stream] went live: its title and category, and a tap opens its chat. */
    suspend fun notifyLive(stream: HelixStream) {
        if (!manager.areNotificationsEnabled()) return
        val title = context.getString(R.string.notif_live_title, stream.userName.ifEmpty { stream.userLogin })
        val notification = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            // Only channels in the list have a known picture; the app icon would add nothing.
            .setLargeIcon(if (stream.userLogin in channels.info.value) icons.channel(stream.userLogin).toIcon(context) else null)
            .setContentTitle(title)
            .setContentText(stream.title)
            .setSubText(stream.gameName.ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(stream.title))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(openLiveIntent(context, stream.userLogin))
            .build()
        try {
            manager.notify(LIVE_TAG_PREFIX + stream.userLogin, LIVE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked in the meantime.
        }
    }

    /** Removes that notice once the connection is back. */
    fun clearNotListening() = manager.cancel(NOT_LISTENING_ID)

    /** Loads the pictures and posts [items], mentions in one channel, as one update. */
    suspend fun notify(items: List<ChatItem>) {
        if (items.isEmpty() || !manager.areNotificationsEnabled()) return
        val icon = icons.channel(items.first().channel)
        // Fills the cache for these chatters; older lines use what is cached.
        loadSenderIcons(items.mapNotNull { it.login })
        post(items, icon, newestMention = items.last().id)
    }

    /**
     * What a mention or whisper gets while Chatter is on screen: a short vibration instead of a
     * banner over the chat.
     *
     * It respects the user's choices for that notification: no vibration for a silenced channel, on
     * a silent phone or in Do Not Disturb. It is sent as a notification vibration, so the system's
     * vibration switch for notifications applies too.
     */
    fun buzz(channelId: String) {
        if (!manager.areNotificationsEnabled()) return
        val importance = nm.getNotificationChannel(channelId)?.importance ?: NotificationManager.IMPORTANCE_HIGH
        if (importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
        )
    }

    /** Shows a message sent from the notification in that conversation. */
    suspend fun showSent(channel: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        post(
            listOf(
                ChatItem(
                    id = "notification-reply-${System.nanoTime()}", channel = channel, kind = MessageKind.Chat,
                    timestamp = System.currentTimeMillis(), text = text, isOwn = true,
                ),
            ),
            icons.channel(channel),
        )
    }

    /** Shows in the conversation that a reply was not sent; the keyboard is gone by then. */
    suspend fun showSendFailed(channel: String) {
        if (!manager.areNotificationsEnabled()) return
        val text = context.getString(R.string.notif_reply_failed)
        post(
            listOf(
                ChatItem(
                    id = "notification-failed-${System.nanoTime()}", channel = channel, kind = MessageKind.Notice,
                    timestamp = System.currentTimeMillis(), text = text, displayName = context.getString(R.string.app_name),
                ),
            ),
            icons.channel(channel),
        )
    }

    /** [items] are lines of one channel. [newestMention] is null for the user's own lines. */
    @Synchronized
    private fun post(items: List<ChatItem>, icon: IconCompat, newestMention: String? = null) {
        val item = items.last()
        newestMention?.let { jumpTargets[item.channel] = it }
        val jumpTo = jumpTargets[item.channel]
        val lines = recent.getOrPut(item.channel) { ArrayDeque() }
        lines.addAll(items)
        while (lines.size > 6) lines.removeFirst()

        val name = channelName(item.channel)
        val shortcutId = setUpConversation(item.channel, name, icon)
        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("#${item.channel}")
            .setGroupConversation(true)
        lines.forEach { m ->
            // MessagingStyle reads a null person as the user.
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
            .setContentIntent(openChannelIntent(context, item.channel, jumpTo))
            .addAction(replyAction(item.channel))
            .apply { if (settings.value.bubbles) setBubbleMetadata(bubbleMetadata(item.channel, icon, jumpTo)) }
            .build()
        try {
            manager.notify(item.channel.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission revoked in the meantime.
        }
    }

    /** Removes a channel's notification once the user looks at it. */
    @Synchronized
    fun clear(channel: String) {
        recent.remove(channel)
        jumpTargets.remove(channel)
        manager.cancel(channel.hashCode())
    }

    private fun channelName(channel: String): String = channels.info.value[channel]?.displayName ?: channel

    /**
     * The senders' Twitch avatars, looked up once per chatter, all new ones in one request. Costs a
     * download per new name, so it is a setting and skipped while saving data. Cached pictures are
     * always used.
     */
    private suspend fun loadSenderIcons(logins: List<String>) {
        if (!settings.value.senderAvatars || saveData.value) return
        val missing = logins.map { it.lowercase() }.distinct().filterNot(senderIcons::containsKey).take(MAX_SENDER_ICONS)
        if (missing.isEmpty()) return
        val users = runCatching { helix.users(missing) }.getOrNull() ?: return
        coroutineScope {
            users.forEach { user ->
                val url = user.profileImageUrl.ifEmpty { null } ?: return@forEach
                launch {
                    icons.load(url)?.let {
                        // Mentions keep coming from new people; the cache must not grow without bound.
                        if (senderIcons.size >= MAX_SENDER_ICONS) senderIcons.clear()
                        senderIcons[user.login.lowercase()] = it
                    }
                }
            }
        }
    }

    // ---- Whispers --------------------------------------------------------------------------

    /**
     * Posts or extends the conversation with the sender, if the account the whisper was sent to is
     * still the active one; see [WhisperConversations].
     */
    suspend fun notifyWhisper(whisper: InboxWhisper) {
        if (!manager.areNotificationsEnabled()) return
        // Fills the cache for this sender; older lines use what is cached.
        loadSenderIcons(listOf(whisper.login))
        addWhisperLine(
            owner = whisper.owner,
            login = whisper.login,
            name = whisper.displayName,
            userId = whisper.userId,
            line = WhisperConversations.Line(whisper.text, whisper.timestamp, own = false),
        )
    }

    /** Shows an answer [account] sent from the notification in that conversation. */
    fun showWhisperSent(account: String?, login: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        val line = WhisperConversations.Line(text, System.currentTimeMillis(), own = true)
        addWhisperLine(account, login, name = null, userId = null, line = line)
    }

    /**
     * Shows in the conversation why an answer was not sent; Twitch refuses whispers for reasons the
     * app cannot predict.
     */
    fun showWhisperFailed(account: String?, login: String, reason: String) {
        if (!manager.areNotificationsEnabled()) return
        val line = WhisperConversations.Line(reason, System.currentTimeMillis(), own = true)
        addWhisperLine(account, login, name = null, userId = null, line = line)
    }

    /**
     * Removes all mentions and whispers when the active account changes. Otherwise a whisper would
     * keep showing someone else's private message, and its reply would go out as the wrong account.
     */
    @Synchronized
    fun clearConversations() {
        recent.keys.toList().forEach { clear(it) }
        clearWhispers()
    }

    /** Removes the whisper notifications once the whisper tab is opened. */
    @Synchronized
    fun clearWhispers() {
        whisperThreads.clear().forEach { manager.cancel(it, WHISPER_NOTIFICATION_ID) }
    }

    /** Under the same lock as [clearConversations], so nothing is posted after it cleared. */
    @Synchronized
    private fun addWhisperLine(owner: String?, login: String, name: String?, userId: String?, line: WhisperConversations.Line) {
        val thread = whisperThreads.add(owner, login, name, userId, line) ?: return
        val key = login.lowercase()

        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val sender = Person.Builder().setName(thread.name).setKey(key).setIcon(senderIcons[key]).build()
        val style = NotificationCompat.MessagingStyle(me)
        // MessagingStyle reads a null person as the user.
        thread.lines.forEach { style.addMessage(it.text, it.timestamp, if (it.own) null else sender) }

        val notification = NotificationCompat.Builder(context, CHANNEL_WHISPERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_WHISPERS)
            .setAutoCancel(true)
            .setContentIntent(openWhispersIntent())
            .addAction(whisperReplyAction(login, thread.userId, thread.owner))
            // On a lock screen that hides private content, only "a new whisper" is shown.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_WHISPERS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notif_whisper_public))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .build(),
            )
            .build()
        try {
            // Tagged with the login: one notification per person, separate from the mentions, which
            // have no tag.
            manager.notify(key, WHISPER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked in the meantime.
        }
    }

    private fun openWhispersIntent(): PendingIntent {
        val intent = appLaunchIntent(context, channel = null).putExtra(EXTRA_INBOX_TAB, INBOX_TAB_WHISPERS)
        return PendingIntent.getActivity(
            context, WHISPER_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Answers as [owner], the account the whisper was sent to. */
    private fun whisperReplyAction(login: String, userId: String?, owner: String): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setData(replyUri(REPLY_WHISPER, login))
            .putExtra(EXTRA_WHISPER, login)
            .putExtra(EXTRA_WHISPER_USER_ID, userId)
            .putExtra(EXTRA_ACCOUNT, owner)
        val pending = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val label = context.getString(R.string.notif_reply)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, pending)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            // It writes as the user, so a locked phone asks to unlock first.
            .setAuthenticationRequired(true)
            .build()
    }

    // ------------------------------------------------------------------------------------------

    /**
     * The notification channel and the conversation shortcut a notification points to, set up when
     * the channel's name or picture changed. [dev.chatter.app.util.ChannelShortcuts] publishes the
     * same shortcut, but it has to exist before the notification arrives, and a mention can arrive
     * before the channel list, e.g. right after restoring a backup. Returns the shortcut's id.
     */
    private fun setUpConversation(channel: String, name: String, icon: IconCompat): String {
        val before = conversations[channel]
        if (before == null || before.first != name || before.second !== icon) {
            ensureChannel(channel, name)
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, channelShortcut(context, channel, name, icon)) }
            conversations[channel] = name to icon
        }
        return channelShortcutId(channel)
    }

    /**
     * Reply without opening the app. The intent must be mutable: Android writes the typed text into
     * it before handing it to [ReplyReceiver].
     */
    private fun replyAction(channel: String): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java)
            .setData(replyUri(REPLY_CHANNEL, channel))
            .putExtra(EXTRA_CHANNEL, channel)
            .putExtra(EXTRA_ACCOUNT, activeAccount())
        val pending = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val label = context.getString(R.string.notif_reply)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, pending)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            // It writes as the user, so a locked phone asks to unlock first.
            .setAuthenticationRequired(true)
            .build()
    }

    private fun bubbleMetadata(channel: String, icon: IconCompat, message: String?): NotificationCompat.BubbleMetadata {
        // The data URI makes each channel its own document, so two channels can bubble side by
        // side.
        val intent = Intent(context, BubbleActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("chatter://channel/$channel".toUri())
            .putExtra(EXTRA_CHANNEL, channel)
            .putExtra(EXTRA_MESSAGE, message)
        val pending = PendingIntent.getActivity(
            context, channel.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.BubbleMetadata.Builder(pending, icon)
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            // Keeps the notification; an unopened bubble is easy to miss.
            .setSuppressNotification(false)
            .build()
    }

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        /** Groups the per-channel mention channels in the system settings. */
        private const val GROUP_MENTIONS = "mentions"
        /** The single mentions channel of older versions. */
        private const val LEGACY_CHANNEL_MENTIONS = "mentions"
        private const val GROUP = "mentions"
        const val CHANNEL_WHISPERS = "whispers"
        private const val GROUP_WHISPERS = "whispers"
        /** Whisper notifications are told apart by their tag (the sender's login), not by id. */
        private const val WHISPER_NOTIFICATION_ID = 2
        const val CHANNEL_PROBLEMS = "problems"
        const val CHANNEL_LIVE = "live"
        private const val LIVE_NOTIFICATION_ID = 4
        /** One live notification per channel, told apart by tag. */
        private const val LIVE_TAG_PREFIX = "live:"
        private const val NOT_LISTENING_ID = 3

        /** The notification channel for mentions in [channel]. */
        fun mentionChannelId(channel: String): String = "mentions:$channel"
        private const val BUBBLE_HEIGHT_DP = 620
        private const val MAX_SENDER_ICONS = 100
        /** Key of the text typed into the reply action. */
        const val KEY_REPLY = "reply"

        private const val REPLY_CHANNEL = "channel"
        private const val REPLY_WHISPER = "whisper"

        /**
         * Makes each conversation's reply PendingIntent distinct. Android compares PendingIntents
         * by target, action and data, never by extras; with the conversation only in the extras,
         * two conversations with the same hash shared one PendingIntent and replies went to the
         * wrong person.
         */
        internal fun replyUri(kind: String, target: String): Uri =
            Uri.Builder().scheme("chatter").authority("reply").appendPath(kind).appendPath(target).build()

        /** Opens [channel]'s chat, adding it to the list if it is not there; see LiveAlerts. */
        fun openLiveIntent(context: Context, channel: String): PendingIntent = PendingIntent.getActivity(
            context, (LIVE_TAG_PREFIX + channel).hashCode(),
            appLaunchIntent(context, null).putExtra(EXTRA_LIVE_CHANNEL, channel),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Opens [channel]'s chat, scrolled to [message] if given. */
        fun openChannelIntent(context: Context, channel: String?, message: String? = null): PendingIntent {
            val intent = appLaunchIntent(context, channel).putExtra(EXTRA_MESSAGE, message)
            return PendingIntent.getActivity(
                context, channel?.hashCode() ?: 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
