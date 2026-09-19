package dev.chatter.app.util

import dev.chatter.app.emotes.Emote

/** Pure helpers for input autocomplete, kept free of Android so they can be unit-tested. */
object Autocomplete {
    /** The word the cursor is in (from its start up to the next space). */
    data class Word(val start: Int, val end: Int, val text: String)

    fun currentWord(text: String, cursor: Int): Word? {
        if (cursor < 0 || cursor > text.length) return null
        var start = cursor
        while (start > 0 && text[start - 1] != ' ') start--
        var end = cursor
        while (end < text.length && text[end] != ' ') end++
        if (start == end) return null
        return Word(start, end, text.substring(start, end))
    }

    /**
     * Ranking: exact-case prefix, then case-insensitive prefix, then substring.
     * Shorter names first within a group, so "LUL" comes before "LULW".
     */
    fun rankEmotes(query: String, emotes: List<Emote>, limit: Int = 40): List<Emote> {
        if (query.isEmpty()) return emptyList()
        val exact = ArrayList<Emote>()
        val prefix = ArrayList<Emote>()
        val contains = ArrayList<Emote>()
        for (e in emotes) when {
            e.name.startsWith(query) -> exact += e
            e.name.startsWith(query, ignoreCase = true) -> prefix += e
            e.name.contains(query, ignoreCase = true) -> contains += e
        }
        val byLength = compareBy<Emote>({ it.name.length }, { it.name })
        return (exact.sortedWith(byLength) + prefix.sortedWith(byLength) + contains.sortedWith(byLength)).take(limit)
    }

    /** Users keep their recency order (most recently active first); prefix matches come first. */
    fun rankUsers(query: String, users: List<String>, limit: Int = 30): List<String> {
        val q = query.removePrefix("@")
        if (q.isEmpty()) return users.take(limit)
        val prefix = users.filter { it.startsWith(q, ignoreCase = true) }
        val contains = users.filter { !it.startsWith(q, ignoreCase = true) && it.contains(q, ignoreCase = true) }
        return (prefix + contains).take(limit)
    }

    /** Replaces [word] with [replacement] plus a trailing space. Returns the new text and cursor. */
    fun replace(text: String, word: Word, replacement: String): Pair<String, Int> {
        val after = text.substring(word.end)
        val insert = if (after.startsWith(" ")) replacement else "$replacement "
        val newText = text.substring(0, word.start) + insert + after
        return newText to (word.start + insert.length + if (after.startsWith(" ")) 1 else 0)
    }

    /** Inserts [value] at the cursor, padding it with spaces where needed. */
    fun insert(text: String, cursor: Int, value: String): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        val before = text.substring(0, c)
        val after = text.substring(c)
        val lead = if (before.isEmpty() || before.endsWith(" ")) "" else " "
        val insert = "$lead$value "
        return (before + insert + after.trimStart()) to (before.length + insert.length)
    }
}
