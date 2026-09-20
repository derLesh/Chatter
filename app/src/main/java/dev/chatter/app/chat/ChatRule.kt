package dev.chatter.app.chat

import kotlinx.serialization.Serializable

/** What part of a message a rule looks at. */
enum class RuleTarget { Message, Author, Any }

/** What a rule does with a message it matches. */
enum class RuleAction {
    /** Paint the message, nothing more. */
    Highlight,

    /** Paint it and treat it like a mention: notification, inbox, unread count. */
    Notify,

    /** Drop the message before it ever reaches the chat. */
    Hide,
}

/**
 * One rule the user wrote: "when a message looks like this, do that". Rules are what turns
 * Chatter's fixed keyword lists into something one can actually shape — a regex for a bot's
 * output, a color for one streamer's name, a quiet highlight that does not buzz the phone.
 */
@Serializable
data class ChatRule(
    val id: String,
    /** Plain words (matched whole, like a mention) or a regular expression, see [regex]. */
    val pattern: String,
    val enabled: Boolean = true,
    val regex: Boolean = false,
    val target: RuleTarget = RuleTarget.Message,
    val action: RuleAction = RuleAction.Highlight,
    /** ARGB color for the highlight, or null for the mention color from the settings. */
    val color: Int? = null,
    /** Channel login the rule is limited to, or null for every channel. */
    val channel: String? = null,
)
