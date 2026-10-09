package dev.chatter.app.chat

/** Finds the user's name or keywords as whole words; see [WordMatcher]. */
class MentionMatcher(login: String, keywords: List<String>) {
    private val words = WordMatcher.of((listOf(login) + keywords).map { it.trim().removePrefix("@") })

    fun matches(text: String): Boolean = words?.containsIn(text) == true
}
