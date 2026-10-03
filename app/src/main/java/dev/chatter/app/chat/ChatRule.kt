package dev.chatter.app.chat

import kotlinx.serialization.Serializable

/** The part of a message a rule looks at. */
enum class RuleTarget { Message, Author, Any }

/** What a rule does with a matching message. */
enum class RuleAction {
    /** Highlight only. */
    Highlight,

    /** Highlight and treat as a mention: notification, inbox, unread count. */
    Notify,

    /** Drop the message before it reaches the chat. */
    Hide,
}

/**
 * A user rule: "when a message looks like this, do that", e.g. a regex for a bot or a color for one
 * streamer's name.
 */
@Serializable
data class ChatRule(
    val id: String,
    /** Plain words (matched whole, like a mention) or a regular expression; see [regex]. */
    val pattern: String,
    val enabled: Boolean = true,
    val regex: Boolean = false,
    val target: RuleTarget = RuleTarget.Message,
    val action: RuleAction = RuleAction.Highlight,
    /** ARGB highlight color, or null for the mention color from the settings. */
    val color: Int? = null,
    /** Channel login the rule is limited to, or null for all channels. */
    val channel: String? = null,
)
