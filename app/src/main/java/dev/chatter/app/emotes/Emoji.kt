package dev.chatter.app.emotes

import android.content.Context
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The groups emoji keyboards sort by, in their usual order. */
enum class EmojiGroup(val key: String) {
    Smileys("smileys"),
    People("people"),
    Animals("animals"),
    Food("food"),
    Travel("travel"),
    Activities("activities"),
    Objects("objects"),
    Symbols("symbols"),
    Flags("flags"),
}

/** One emoji and the `:shortcodes:` that type it, without the colons. */
data class Emoji(val value: String, val group: EmojiGroup, val shortcodes: List<String>)

/** Reads and searches the emoji list shipped as `assets/emoji.tsv`. Free of Android for the tests. */
object EmojiCatalog {
    /** Lines of "emoji, group, shortcodes separated by spaces", tab-separated; `#` starts a comment. */
    fun parse(text: String): List<Emoji> {
        val groups = EmojiGroup.entries.associateBy { it.key }
        return text.lineSequence().mapNotNull { line ->
            val fields = line.trimEnd('\r').split('\t')
            if (line.startsWith("#") || fields.size != 3) return@mapNotNull null
            val group = groups[fields[1]] ?: return@mapNotNull null
            val shortcodes = fields[2].split(' ').filter { it.isNotEmpty() }
            if (fields[0].isEmpty() || shortcodes.isEmpty()) null else Emoji(fields[0], group, shortcodes)
        }.toList()
    }

    /**
     * Emoji whose shortcode starts with [query], then those containing it; shorter shortcodes first
     * within each, so ":smi" offers smile before smiley_cat. Each emoji once, with the shortcode
     * that matched.
     */
    fun search(query: String, emoji: List<Emoji>, limit: Int = 30): List<Pair<Emoji, String>> {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase()
        val prefix = ArrayList<Pair<Emoji, String>>()
        val contains = ArrayList<Pair<Emoji, String>>()
        for (e in emoji) {
            val starting = e.shortcodes.filter { it.startsWith(q) }.minByOrNull { it.length }
            if (starting != null) {
                prefix += e to starting
                continue
            }
            e.shortcodes.filter { q in it }.minByOrNull { it.length }?.let { contains += e to it }
        }
        val byLength = compareBy<Pair<Emoji, String>>({ it.second.length }, { it.second })
        return (prefix.sortedWith(byLength) + contains.sortedWith(byLength)).take(limit)
    }

    /** The shortcode typed so far, if [word] is one: a colon and at least two characters after it. */
    fun typedShortcode(word: String): String? {
        if (!word.startsWith(":") || word.length < 3) return null
        val code = word.substring(1).removeSuffix(":")
        return code.takeIf { c -> c.isNotEmpty() && c.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '+' } }
    }
}

/**
 * The shipped emoji the phone's font can draw; Chatter bundles no images, so one the font lacks
 * would show as a box. Read once, on first use.
 */
class EmojiRepository(private val context: Context) {
    private val lock = Mutex()
    private var loaded: List<Emoji>? = null

    suspend fun all(): List<Emoji> = lock.withLock {
        loaded ?: withContext(Dispatchers.IO) {
            val text = runCatching { context.assets.open("emoji.tsv").bufferedReader().use { it.readText() } }.getOrDefault("")
            val paint = Paint()
            EmojiCatalog.parse(text).filter { paint.hasGlyph(it.value) }
        }.also { loaded = it }
    }
}
