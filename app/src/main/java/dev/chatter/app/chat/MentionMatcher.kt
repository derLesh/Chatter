package dev.chatter.app.chat

/**
 * Finds the user's name (or custom keywords) as a whole word, case-insensitive.
 * "@lukas", "lukas:" and "Lukas" match, "lukasz" does not.
 */
class MentionMatcher(login: String, keywords: List<String>) {
    private val regex: Regex? = (listOf(login) + keywords)
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotEmpty() }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|") { Regex.escape(it) }
        ?.let { Regex("(?<![\\p{L}\\p{N}_])(?:$it)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) }

    fun matches(text: String): Boolean = regex?.containsMatchIn(text) == true
}
