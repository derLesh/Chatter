package me.lesh.chatter.chat

/** Everything exchanged with one person in whispers; see [conversationsOf]. */
data class WhisperConversation(
    /** Lower case; whispers are grouped by it. */
    val login: String,
    val userId: String?,
    val displayName: String,
    /** ARGB from their whispers, or null if they never picked a color. */
    val color: Int?,
    val latest: InboxWhisper,
    val unread: Int,
)

/**
 * [whispers], newest first, as one conversation per person, the most recent first. Twitch numbers
 * whispers per thread, but a thread is always between the same two people, so the login is enough.
 */
fun conversationsOf(whispers: List<InboxWhisper>): List<WhisperConversation> =
    whispers.groupBy { it.login.lowercase() }.map { (login, messages) ->
        // Their own whispers carry their spelling and color; ours only what we typed.
        val received = messages.filterNot { it.outgoing }
        WhisperConversation(
            login = login,
            userId = messages.firstNotNullOfOrNull { it.userId },
            displayName = (received.firstOrNull() ?: messages.first()).displayName,
            color = received.firstNotNullOfOrNull { it.color },
            latest = messages.first(),
            unread = received.count { !it.read },
        )
    }

/** The whispers exchanged with [login], newest first. */
fun whispersWith(whispers: List<InboxWhisper>, login: String): List<InboxWhisper> =
    whispers.filter { it.login.equals(login, ignoreCase = true) }
