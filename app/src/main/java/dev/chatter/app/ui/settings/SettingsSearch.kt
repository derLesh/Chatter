package dev.chatter.app.ui.settings

import java.text.Normalizer
import java.util.Locale

/** Matching for the settings search. Lenient: ignores accents and umlauts and word order. */
object SettingsSearch {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")
    private val number = Regex("\\s*:?\\s*%\\d+\\$[a-z]")

    /** Lower case, without accents, "ß" as "ss". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).replace("ß", "ss")

    /** Whether every word of [query] occurs in [texts]; a blank query matches nothing. */
    fun matches(query: String, texts: List<String>): Boolean {
        val words = normalize(query).split(spaces).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val haystack = texts.joinToString(" ") { normalize(it) }
        return words.all { it in haystack }
    }

    /** A title with a placeholder, like "Text size: %1$d", reduced to the setting's name. */
    fun plainTitle(title: String): String = title.replace(number, "").trim()
}
