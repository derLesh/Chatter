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

/** Reads and searches `assets/emoji.tsv`. Free of Android for the tests. */
object EmojiCatalog {
    /** One emoji per line: the emoji, its group and its shortcodes, tab-separated; `#` comments. */
    fun parse(text: String): List<Emoji> {
        val groups = EmojiGroup.entries.associateBy { it.key }
        return text.lineSequence().mapNotNull { line ->
            if (line.startsWith("#")) return@mapNotNull null
            val fields = line.trimEnd('\r').split('\t')
            val group = fields.getOrNull(1)?.let(groups::get) ?: return@mapNotNull null
            val shortcodes = fields.getOrNull(2)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
            if (fields.size != 3 || fields[0].isEmpty() || shortcodes.isEmpty()) null else Emoji(fields[0], group, shortcodes)
        }.toList()
    }

    /**
     * Emoji whose shortcode starts with [query], then those containing it, each with its shortest
     * match; shorter ones first, so ":smi" offers smile before smiley_cat.
     */
    fun search(query: String, emoji: List<Emoji>, limit: Int = 30): List<Pair<Emoji, String>> {
        if (query.isEmpty()) return emptyList()
        val q = query.lowercase()
        val prefix = ArrayList<Pair<Emoji, String>>()
        val contains = ArrayList<Pair<Emoji, String>>()
        for (e in emoji) {
            e.shortestCode { it.startsWith(q) }?.let { prefix += e to it }
                ?: e.shortestCode { q in it }?.let { contains += e to it }
        }
        val byLength = compareBy<Pair<Emoji, String>>({ it.second.length }, { it.second })
        return (prefix.sortedWith(byLength) + contains.sortedWith(byLength)).take(limit)
    }

    private inline fun Emoji.shortestCode(matches: (String) -> Boolean): String? {
        var best: String? = null
        for (code in shortcodes) if (matches(code) && (best == null || code.length < best.length)) best = code
        return best
    }

    /** The shortcode typed so far, if [word] is one: a colon and at least two characters. */
    fun typedShortcode(word: String): String? {
        if (!word.startsWith(":") || word.length < 3) return null
        return word.substring(1).removeSuffix(":").takeIf { code -> code.all { it.isLetterOrDigit() || it in "_-+" } }
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
