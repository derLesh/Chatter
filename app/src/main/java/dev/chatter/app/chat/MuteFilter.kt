package dev.chatter.app.chat

/**
 * Decides which messages never make it into the chat at all.
 *
 * Keywords match as whole words, case-insensitive, just like [MentionMatcher] does, so muting
 * "sub" does not swallow every "subscribe". Both the message text and the name of whoever wrote
 * it are checked, which lets a keyword mute a bot by name.
 *
 * Blocked users come straight from the Twitch block list and are matched on their login.
 */
class MuteFilter(keywords: List<String> = emptyList(), blocked: Set<String> = emptySet()) {
    private val blocked: Set<String> = blocked.mapTo(HashSet()) { it.lowercase() }

    private val regex: Regex? = keywords
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { Regex.escape(it) }
        ?.let { Regex("(?<![\\p{L}\\p{N}_])(?:$it)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) }

    /** True if the message should be dropped. The user's own messages are never muted. */
    fun mutes(item: ChatItem): Boolean =
        if (item.isOwn) false else mutes(item.login, item.displayName, item.text)

    /** The same test for something that never reaches a channel buffer, like a whisper. */
    fun mutes(login: String?, displayName: String?, text: String): Boolean {
        val sender = login?.lowercase()
        if (sender != null && sender in blocked) return true
        val regex = regex ?: return false
        return regex.containsMatchIn(text) ||
            (sender != null && regex.containsMatchIn(sender)) ||
            (displayName?.let { regex.containsMatchIn(it) } == true)
    }
}
