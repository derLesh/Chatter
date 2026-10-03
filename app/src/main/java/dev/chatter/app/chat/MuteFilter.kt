package dev.chatter.app.chat

/**
 * Decides which messages never reach the chat.
 *
 * Keywords match whole words, case-insensitive, like [MentionMatcher], so muting "sub" does not
 * hide "subscribe". Both text and author name are checked, so a keyword can mute a bot by name.
 * Blocked users come from the Twitch block list and match by login.
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

    /** The same check for things outside the channel buffers, like whispers. */
    fun mutes(login: String?, displayName: String?, text: String): Boolean {
        val sender = login?.lowercase()
        if (sender != null && sender in blocked) return true
        val regex = regex ?: return false
        return regex.containsMatchIn(text) ||
            (sender != null && regex.containsMatchIn(sender)) ||
            (displayName?.let { regex.containsMatchIn(it) } == true)
    }
}
