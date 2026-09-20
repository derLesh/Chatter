package dev.chatter.app.chat

import dev.chatter.app.badges.Badge
import dev.chatter.app.emotes.Emote

/** A piece of a chat message, pre-computed once when the message arrives. */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class EmoteSeg(val emote: Emote, val overlays: List<Emote> = emptyList()) : Segment
    data class Link(val text: String, val url: String) : Segment
    /**
     * "@name". [login] and [color] are set only while that user is chatting in the channel,
     * so unknown names stay in the default text color.
     */
    data class Mention(val name: String, val login: String? = null, val color: Int? = null) : Segment
}

enum class MessageKind { Chat, Action, UserNotice, Notice }

/**
 * The parts of a message that only drawing it needs: its emote, link and mention segments, and
 * the sender's badges. They are worked out the first time something asks for them, because
 * everything the app *decides* about a message — is it muted, is it a mention, does a rule paint
 * it — reads nothing but its plain text.
 *
 * That is what makes a chat cheap to keep open in the background: messages nobody is watching
 * arrive, are counted, and go into the buffer without ever being turned into emotes. The same
 * goes for the hundred lines of history fetched on join, of which a screenful is ever seen.
 *
 * Building reads the emote tables and the chatter registry, which belong to [ChatRepository]'s
 * worker. The repository therefore builds a message before handing it to the UI (see its
 * `snapshot`), so nothing is ever built on the main thread.
 */
class MessageBody private constructor(
    private var build: (() -> Parts)?,
    @Volatile private var parts: Parts?,
) {
    private class Parts(val segments: List<Segment>, val badges: List<Badge>)

    val segments: List<Segment> get() = parts().segments
    val badges: List<Badge> get() = parts().badges

    /** Builds the parts now, on the calling thread, if they are not built already. */
    fun prepare() {
        parts()
    }

    private fun parts(): Parts = parts ?: synchronized(this) {
        parts ?: build!!().also {
            parts = it
            // Lets go of whatever the lambda was holding on to.
            build = null
        }
    }

    companion object {
        val EMPTY = of(emptyList())

        /** For messages the app writes itself, which have nothing to work out. */
        fun of(segments: List<Segment>, badges: List<Badge> = emptyList()) =
            MessageBody(null, Parts(segments, badges))

        fun lazily(segments: () -> List<Segment>, badges: () -> List<Badge>) =
            MessageBody({ Parts(segments(), badges()) }, null)
    }
}

data class ReplyInfo(
    val parentId: String,
    val parentLogin: String,
    val parentDisplayName: String,
    val parentBody: String,
)

/** Immutable, render-ready chat line. */
data class ChatItem(
    val id: String,
    val channel: String,
    val kind: MessageKind,
    val timestamp: Long,
    val login: String? = null,
    val displayName: String? = null,
    /** ARGB color from the `color` tag, or null if the user never picked one. */
    val color: Int? = null,
    /** Everything only the drawing of this message needs — see [MessageBody]. */
    val body: MessageBody = MessageBody.EMPTY,
    /** Header for sub/raid notices, or the whole text for NOTICE/system lines. */
    val systemText: String? = null,
    /** The plain message text (for copy / reply preview). */
    val text: String = "",
    val isMention: Boolean = false,
    /** ARGB background a highlight rule painted this message with, if one matched. */
    val highlight: Int? = null,
    /** Twitch's `first-msg`: the user's very first message in this channel, ever. */
    val isFirstMessage: Boolean = false,
    val isOwn: Boolean = false,
    val reply: ReplyInfo? = null,
    val deleted: Boolean = false,
    val historical: Boolean = false,
    /**
     * Every other message in a channel buffer, fixed when the message is added. Stored on the
     * item (instead of using the list position) so the pattern doesn't flip when old messages
     * are dropped from the top.
     */
    val alternate: Boolean = false,
) {
    val canReply: Boolean get() = kind == MessageKind.Chat || kind == MessageKind.Action

    val segments: List<Segment> get() = body.segments
    val badges: List<Badge> get() = body.badges
}
