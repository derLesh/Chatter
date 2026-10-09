package dev.chatter.app.chat

/**
 * Decides which messages never reach the chat.
 *
 * Keywords match whole words ([WordMatcher]), so muting "sub" does not hide "subscribe". Both text and author name are checked, so a keyword can mute a bot by name.
 * Blocked users come from the Twitch block list and match by login.
 */
class MuteFilter(keywords: List<String> = emptyList(), blocked: Set<String> = emptySet()) {
    private val blocked: Set<String> = blocked.mapTo(HashSet()) { it.lowercase() }

    private val words = WordMatcher.of(keywords)

    /** True if the message should be dropped. The user's own messages are never muted. */
    fun mutes(item: ChatItem): Boolean =
        if (item.isOwn) false else mutes(item.login, item.displayName, item.text)

    /** The same check for things outside the channel buffers, like whispers. */
    fun mutes(login: String?, displayName: String?, text: String): Boolean {
        val sender = login?.lowercase()
        if (sender != null && sender in blocked) return true
        val words = words ?: return false
        return words.containsIn(text) ||
            (sender != null && words.containsIn(sender)) ||
            // Most display names are the login in other case, which needs no second look.
            (displayName?.takeUnless { it.equals(login, ignoreCase = true) }?.let(words::containsIn) == true)
    }
}
