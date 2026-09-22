package dev.chatter.app.chat

import dev.chatter.app.irc.IrcMessage
import java.util.UUID

/** What the settings make of a message as it arrives. Replaced as a whole when they change. */
data class ChatFilters(
    val mentions: MentionMatcher = MentionMatcher("", emptyList()),
    val muted: MuteFilter = MuteFilter(),
    val rules: RuleEngine = RuleEngine(),
)

/**
 * What Twitch says, and what the app makes of it.
 *
 * One message at a time, on the chat worker: build it, let the mute list and the user's rules have
 * their say, put it in its channel, and tell whoever needs to know. What it changes lives in
 * [MessageBuffers], [Rooms] and [ChatterRegistry]; what it cannot do itself — ringing a
 * notification, fetching a channel's emotes — it hands out through the callbacks it was given.
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
    /** A mention, and whether the user was looking at its channel when it arrived. */
    private val onMention: (item: ChatItem, watched: Boolean) -> Unit,
    /** A whisper, and whether the whisper tab was in front of the user. */
    private val onWhisper: (whisper: InboxWhisper, watched: Boolean) -> Unit,
    /** The first time a channel names its Twitch id: its emotes and badges can be fetched now. */
    private val onRoomFound: (channelId: String) -> Unit,
) {
    /**
     * When the newest live message of each channel was written, so that a reconnect can ask the
     * history service for the gap rather than for everything again.
     */
    private val lastLive = HashMap<String, Long>()

    fun lastLive(channel: String): Long = lastLive[channel] ?: 0L

    /**
     * The user's own messages that are on screen but not yet named by Twitch, oldest first, per
     * channel. Twitch never sends them back; the only word of their real id is the USERSTATE that
     * answers each PRIVMSG it accepted, or a NOTICE for one it refused. Both come in the order the
     * messages were sent, so the oldest one waiting is always the one being answered.
     */
    private val unconfirmed = HashMap<String, ArrayDeque<ChatItem>>()

    /** A message the user just sent, shown under a stand-in id until Twitch names it. */
    fun sent(item: ChatItem) {
        buffers.add(item)
        unconfirmed.getOrPut(item.channel) { ArrayDeque() }.addLast(item)
    }

    /**
     * The sent message an answer that arrives now is about. Twitch answers within a second; one
     * still waiting after [ECHO_TIMEOUT_MS] lost its answer to a dropped connection, and handing
     * it the id of a later message would put a reply under the wrong words.
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
        // An answer from the connection before is not coming any more.
        unconfirmed.clear()
    }

    fun handle(msg: IrcMessage) {
        val channel = msg.channel
        when (msg.command) {
            "PRIVMSG", "USERNOTICE" -> if (channel != null) onChatMessage(channel, msg)
            "WHISPER" -> InboxWhisper.from(msg)?.let(::onWhisper)
            "NOTICE" -> if (channel != null) {
                // Every refusal of a sent message has an id of this shape; its message stays unnamed.
                if (msg.tag("msg-id")?.startsWith("msg_") == true) answered(channel)
                builder.build(msg, "", null, filters().mentions)?.let(buffers::add)
            }
            "CLEARCHAT" -> if (channel != null) onClearChat(channel, msg)
            "CLEARMSG" -> if (channel != null) {
                msg.tag("target-msg-id")?.let { id -> buffers.markDeleted(channel) { it.id == id } }
            }
            "ROOMSTATE" -> if (channel != null) {
                // The first time a channel names its id is when its emotes can be fetched.
                if (rooms.onRoomState(channel, msg.tags)) rooms.id(channel)?.let(onRoomFound)
            }
            "USERSTATE" -> if (channel != null) {
                rooms.onUserState(channel, msg.tags)
                // Only the USERSTATE that answers a PRIVMSG carries an id: that of the message.
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
        // Muted and hidden messages still count as "seen", so a reconnect does not fetch them again.
        lastLive[channel] = built.timestamp
        if (muted.mutes(built)) return
        val item = rules.apply(built) ?: return
        rememberChatter(channel, item)
        buffers.add(item)
        // Only live messages: the history fetched on join was received long ago.
        if (!item.isOwn) stats.countReceived()
        val watched = windows.isWatching(channel)
        if (!item.isOwn && !watched) buffers.countUnread(channel)
        if (item.isMention) {
            stats.countMention()
            onMention(item, watched)
        }
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

    /** Everyone who writes is somebody an "@" can be completed to and coloured like. */
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
        /** How long a sent message waits for Twitch to name it before it is given up on. */
        const val ECHO_TIMEOUT_MS = 10_000L
    }
}
