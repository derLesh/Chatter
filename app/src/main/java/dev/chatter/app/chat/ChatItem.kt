package dev.chatter.app.chat

import dev.chatter.app.badges.Badge
import dev.chatter.app.emotes.Emote

/** A piece of a chat message, computed once when the message arrives. */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class EmoteSeg(val emote: Emote, val overlays: List<Emote> = emptyList()) : Segment
    data class Link(val text: String, val url: String) : Segment
    /**
     * "@name". [login] and [color] are only set while that user is chatting in the channel, so
     * unknown names keep the default color.
     */
    data class Mention(val name: String, val login: String? = null, val color: Int? = null) : Segment
    /**
     * "Cheer100" in a message with bits: the cheermote's picture in both themes and the amount in
     * its tier's [color].
     */
    data class Cheer(val dark: Emote, val light: Emote, val amount: Int, val color: Int) : Segment
}

enum class MessageKind { Chat, Action, UserNotice, Notice }

/**
 * The parts of a message only drawing needs: emote, link and mention segments, the sender's badges,
 * and the segments of the message a reply answers. Built on first use, because filtering, mentions
 * and rules only read the plain text; messages nobody looks at, in the background or in the
 * history, are never parsed for emotes.
 *
 * Building reads the emote tables and the chatter registry, which belong to [ChatRepository]'s
 * worker, so the repository builds messages before handing them to the UI (see `snapshot`).
 */
class MessageBody private constructor(
    private var build: (() -> Parts)?,
    @Volatile private var parts: Parts?,
    private val worthKeeping: () -> Boolean = { false },
) {
    private class Parts(val segments: List<Segment>, val badges: List<Badge>, val quote: List<Segment>)

    val segments: List<Segment> get() = parts().segments
    val badges: List<Badge> get() = parts().badges
    val quote: List<Segment> get() = parts().quote

    /** Builds the parts now on the calling thread, if not built yet. */
    fun prepare() {
        parts()
    }

    /**
     * The same message, to be built again for emotes that arrived later. A body that was never
     * built, or no longer knows how it was built, is returned as is.
     */
    fun rebuilt(): MessageBody =
        if (parts == null) this else build?.let { MessageBody(it, null, worthKeeping) } ?: this

    private fun parts(): Parts = parts ?: synchronized(this) {
        parts ?: build!!().also {
            parts = it
            // Drops the builder lambda unless the message could still change, i.e. while a provider
            // owes emotes; [rebuilt] needs it then.
            if (!worthKeeping()) build = null
        }
    }

    companion object {
        val EMPTY = of(emptyList())

        /** For lines the app writes itself. */
        fun of(segments: List<Segment>, badges: List<Badge> = emptyList()) =
            MessageBody(null, Parts(segments, badges, emptyList()))

        /**
         * [worthKeeping] is asked after building: true keeps the builder for [rebuilt]. [quote] is
         * the message a reply answers, empty otherwise.
         */
        fun lazily(
            segments: () -> List<Segment>,
            badges: () -> List<Badge>,
            quote: () -> List<Segment> = { emptyList() },
            worthKeeping: () -> Boolean = { false },
        ) = MessageBody({ Parts(segments(), badges(), quote()) }, null, worthKeeping)
    }
}

data class ReplyInfo(
    val parentId: String,
    val parentLogin: String,
    val parentDisplayName: String,
    val parentBody: String,
    /**
     * The first message of the conversation, from `reply-thread-parent-msg-id`. Equals [parentId]
     * for a direct answer to it.
     */
    val threadId: String = parentId,
)

/** An immutable, render-ready chat line. */
data class ChatItem(
    val id: String,
    val channel: String,
    val kind: MessageKind,
    val timestamp: Long,
    val login: String? = null,
    val displayName: String? = null,
    /** ARGB color from the `color` tag, or null if the user never picked one. */
    val color: Int? = null,
    /** What only drawing needs; see [MessageBody]. */
    val body: MessageBody = MessageBody.EMPTY,
    /** Header of sub/raid notices, or the whole text of NOTICE and system lines. */
    val systemText: String? = null,
    /** Plain text, for copying and reply previews. */
    val text: String = "",
    val isMention: Boolean = false,
    /** ARGB background from a matching highlight rule. */
    val highlight: Int? = null,
    /** Twitch's `first-msg`: the user's first message in this channel ever. */
    val isFirstMessage: Boolean = false,
    val isOwn: Boolean = false,
    val reply: ReplyInfo? = null,
    val deleted: Boolean = false,
    val historical: Boolean = false,
    /**
     * Every other message in a channel, fixed when added. Stored on the item so the pattern does
     * not flip when old messages are dropped from the top.
     */
    val alternate: Boolean = false,
    /**
     * Twitch's `source-id` during Shared Chat: each channel of the session gets a copy under its
     * own id, and this one is common to all copies.
     */
    val sharedId: String? = null,
    /** The Shared Chat partner this was written in; null for messages written here. */
    val sourceRoomId: String? = null,
    /** Bits cheered with this message, from the `bits` tag. */
    val bits: Int = 0,
) {
    val canReply: Boolean get() = kind == MessageKind.Chat || kind == MessageKind.Action

    val segments: List<Segment> get() = body.segments
    val badges: List<Badge> get() = body.badges

    /** The message this one answers, as segments; empty if it answers nothing. */
    val quote: List<Segment> get() = body.quote
}
