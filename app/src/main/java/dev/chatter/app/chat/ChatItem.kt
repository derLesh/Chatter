package dev.chatter.app.chat

import dev.chatter.app.badges.Badge
import dev.chatter.app.emotes.Emote

/** A piece of a chat message, pre-computed once when the message arrives. */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class EmoteSeg(val emote: Emote, val overlays: List<Emote> = emptyList()) : Segment
    data class Link(val text: String, val url: String) : Segment
    data class Mention(val name: String) : Segment
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
) {
    val canReply: Boolean get() = kind == MessageKind.Chat || kind == MessageKind.Action
}
