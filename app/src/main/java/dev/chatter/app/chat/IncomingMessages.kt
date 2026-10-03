package dev.chatter.app.chat

import dev.chatter.app.irc.IrcMessage
import java.util.UUID

/** The settings as applied to arriving messages; replaced as a whole when they change. */
data class ChatFilters(
    val mentions: MentionMatcher = MentionMatcher("", emptyList()),
    val muted: MuteFilter = MuteFilter(),
    val rules: RuleEngine = RuleEngine(),
)

/**
 * Handles what Twitch sends, one message at a time on the chat worker: build it, apply the mute
 * list and rules, put it in its channel, and notify the callbacks. State lives in [MessageBuffers],
 * [Rooms] and [ChatterRegistry].
 */
class IncomingMessages(
    private val builder: MessageBuilder,
    private val buffers: MessageBuffers,
    private val rooms: Rooms,
    private val windows: ChatWindows,
    private val chatters: ChatterRegistry,
    private val notices: ChatNotices,
    private val stats: ChatStats,
    private val filters: () -> ChatFilters,
    private val selfLogin: () -> String,
    /** A mention, and whether its channel was on screen. */
    private val onMention: (item: ChatItem, watched: Boolean) -> Unit,
    /** A whisper, and whether the whisper tab was in front. */
    private val onWhisper: (whisper: InboxWhisper, watched: Boolean) -> Unit,
    /**
     * A channel reported its Twitch id for the first time; its emotes and badges can be fetched.
     */
    private val onRoomFound: (channelId: String) -> Unit,
    /** See [SharedChats]. */
    private val sharedChats: SharedChats = SharedChats(),
    /** A channel started sharing its chat. */
    private val onSharedChatStarted: (channel: String) -> Unit = {},
    /** Shared Chat partners whose messages arrived before they were known. */
    private val onPartnersFound: (ids: List<String>) -> Unit = {},
    /** Twitch refused a message the user sent in this channel. */
    private val onRefused: (channel: String) -> Unit = {},
) {
    /**
     * Shared ids of recently reported mentions. A Shared Chat message arrives in every channel of
     * the session and would otherwise notify once per open channel.
     */
    private val mentionedShared = LinkedHashSet<String>()
    /** Time of the newest live message per channel, so a reconnect only fetches the gap. */
    private val lastLive = HashMap<String, Long>()

    fun lastLive(channel: String): Long = lastLive[channel] ?: 0L

    /**
     * The user's sent messages not yet confirmed by Twitch, oldest first, per channel. Twitch never
     * echoes them; their real id only comes with the USERSTATE answering each accepted PRIVMSG, or
     * a NOTICE for a refused one. Both come in send order, so the oldest is the one being answered.
     */
    private val unconfirmed = HashMap<String, ArrayDeque<ChatItem>>()

    /** A message the user just sent, shown under a temporary id until Twitch confirms it. */
    fun sent(item: ChatItem) {
        buffers.add(item)
        unconfirmed.getOrPut(item.channel) { ArrayDeque() }.addLast(item)
    }

    /**
     * The sent message an answer arriving now refers to. Twitch answers within a second; one still
     * waiting after [ECHO_TIMEOUT_MS] lost its answer to a dropped connection.
     */
    private fun answered(channel: String): ChatItem? {
        val waiting = unconfirmed[channel] ?: return null
        val now = System.currentTimeMillis()
        while (waiting.isNotEmpty()) {
            val oldest = waiting.removeFirst()
            if (now - oldest.timestamp <= ECHO_TIMEOUT_MS) return oldest
        }
        return null
    }

    fun clear() {
        lastLive.clear()
        // Answers from the previous connection will not come any more.
        unconfirmed.clear()
        mentionedShared.clear()
    }

    fun handle(msg: IrcMessage) {
        val channel = msg.channel
        when (msg.command) {
            "PRIVMSG", "USERNOTICE" -> if (channel != null) onChatMessage(channel, msg)
            "WHISPER" -> InboxWhisper.from(msg)?.let(::onWhisper)
            "NOTICE" -> if (channel != null) {
                // Every refusal of a sent message has a msg-id of this form. The message was never
                // delivered, so it is struck out and the notice says why.
                if (msg.tag("msg-id")?.startsWith("msg_") == true) answered(channel)?.let { refused ->
                    buffers.markDeleted(channel) { it.id == refused.id }
                    onRefused(channel)
                }
                builder.build(msg, "", null, filters().mentions)?.let(buffers::add)
            }
            "CLEARCHAT" -> if (channel != null) onClearChat(channel, msg)
            "CLEARMSG" -> if (channel != null) {
                msg.tag("target-msg-id")?.let { id -> buffers.markDeleted(channel) { it.id == id } }
            }
            "ROOMSTATE" -> if (channel != null) {
                // The first time a channel reports its id, its emotes can be fetched.
                if (rooms.onRoomState(channel, msg.tags)) rooms.id(channel)?.let(onRoomFound)
            }
            "USERSTATE" -> if (channel != null) {
                rooms.onUserState(channel, msg.tags)
                // Only the USERSTATE answering a PRIVMSG carries an id: the message's.
                msg.tag("id")?.let { id ->
                    answered(channel)?.let { buffers.rename(channel, it.id, id) }
                }
            }
            "GLOBALUSERSTATE" -> rooms.onGlobalUserState(msg.tags)
        }
    }

    private fun onChatMessage(channel: String, msg: IrcMessage) {
        val (mentions, muted, rules) = filters()
        val built = builder.build(msg, selfLogin(), rooms.id(channel), mentions) ?: return
        // Muted and hidden messages count as seen too, so a reconnect does not fetch them again.
        lastLive[channel] = built.timestamp
        // Before the mute list: muted messages also show whether a session is running.
        if (sharedChats.onLiveMessage(channel, msg.tag("room-id") ?: rooms.id(channel), msg.tag("source-room-id"))) {
            onSharedChatStarted(channel)
        }
        built.sourceRoomId?.let { partner ->
            sharedChats.unknown(listOf(partner)).takeIf { it.isNotEmpty() }?.let(onPartnersFound)
        }
        if (muted.mutes(built)) return
        val item = rules.apply(built) ?: return
        rememberChatter(channel, item)
        buffers.add(item)
        // Live messages only; the history was received long ago.
        if (!item.isOwn) stats.countReceived(channel)
        val watched = windows.isWatching(channel)
        if (!item.isOwn && !watched) buffers.countUnread(channel)
        if (item.isMention && firstSighting(item)) {
            stats.countMention()
            onMention(item, watched)
        }
    }

    /** False for a Shared Chat message already reported through another channel of the session. */
    private fun firstSighting(item: ChatItem): Boolean {
        val shared = item.sharedId ?: return true
        if (!mentionedShared.add(shared)) return false
        if (mentionedShared.size > MENTIONS_REMEMBERED) mentionedShared.remove(mentionedShared.first())
        return true
    }

    private fun onWhisper(whisper: InboxWhisper) {
        val muted = filters().muted
        if (muted.mutes(whisper.login, whisper.displayName, whisper.text)) return
        onWhisper(whisper, windows.isWatchingWhispers())
    }

    private fun onClearChat(channel: String, msg: IrcMessage) {
        val target = msg.trailing
        if (target == null) {
            system(channel, notices.chatCleared())
            return
        }
        buffers.markDeleted(channel) { it.login == target }
        val duration = msg.tag("ban-duration")
        system(
            channel,
            if (duration != null) notices.timeout(target, duration.toIntOrNull() ?: 0) else notices.ban(target),
        )
    }

    /** Everyone who writes can be completed and colored after an "@". */
    fun rememberChatter(channel: String, item: ChatItem) {
        val login = item.login ?: return
        chatters.remember(channel, login, item.displayName, item.color)
    }

    private fun system(channel: String, text: String) = buffers.add(
        ChatItem(
            id = UUID.randomUUID().toString(), channel = channel, kind = MessageKind.Notice,
            timestamp = System.currentTimeMillis(), systemText = text, text = text,
        )
    )

    private companion object {
        /** How long a sent message waits for Twitch's confirmation. */
        const val ECHO_TIMEOUT_MS = 10_000L

        /** The copies of a message arrive within moments; a few are enough. */
        const val MENTIONS_REMEMBERED = 64
    }
}
