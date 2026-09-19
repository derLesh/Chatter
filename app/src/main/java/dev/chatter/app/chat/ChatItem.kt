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
    val badges: List<Badge> = emptyList(),
    val segments: List<Segment> = emptyList(),
    /** Header for sub/raid notices, or the whole text for NOTICE/system lines. */
    val systemText: String? = null,
    /** The plain message text (for copy / reply preview). */
    val text: String = "",
    val isMention: Boolean = false,
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
}
