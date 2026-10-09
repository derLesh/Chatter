package dev.chatter.app.chat

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Finds any of a list of words as a whole word, case-insensitive: "lukas" matches "@lukas" and
 * "Lukas:", not "lukasz". Shared by mentions, the mute list and plain rules.
 *
 * Runs for every message, several times, so the matcher is kept and reset: on Android each new
 * Matcher is a native ICU object, which costs more than the match itself.
 */
class WordMatcher private constructor(pattern: Pattern) {
    private val matcher: Matcher = pattern.matcher("")

    @Synchronized
    fun containsIn(text: CharSequence): Boolean = matcher.reset(text).find()

    companion object {
        /** Null when no word is left after trimming, so callers can skip the check. */
        fun of(words: Collection<String>): WordMatcher? {
            val alternatives = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (alternatives.isEmpty()) return null
            val pattern = alternatives.joinToString("|", "(?<![\\p{L}\\p{N}_])(?:", ")(?![\\p{L}\\p{N}_])") { Pattern.quote(it) }
            return WordMatcher(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE))
        }
    }
}
