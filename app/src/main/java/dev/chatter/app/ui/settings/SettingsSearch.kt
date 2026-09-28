package dev.chatter.app.ui.settings

import java.text.Normalizer
import java.util.Locale

/**
 * How the settings search compares what was typed with what a setting is called. Forgiving on
 * purpose: nobody searching the settings types "Über" with its umlaut, or the words of a title in
 * the order they stand in.
 */
object SettingsSearch {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")
    private val number = Regex("\\s*:?\\s*%\\d+\\$[a-z]")

    /** Lower case, without accents and umlaut dots, "ß" as "ss". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT).replace("ß", "ss")

    /** Whether every word of [query] is found somewhere in [texts]; a blank query finds nothing. */
    fun matches(query: String, texts: List<String>): Boolean {
        val words = normalize(query).split(spaces).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val haystack = texts.joinToString(" ") { normalize(it) }
        return words.all { it in haystack }
    }

    /** A title that carries a number, like "Text size: %1$d", as the name of the setting alone. */
    fun plainTitle(title: String): String = title.replace(number, "").trim()
}
