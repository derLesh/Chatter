package dev.chatter.app.chat

/**
 * How a link is written in a message. What a link says about where it leads is its site and the
 * start of its path; the rest is ids and tracking, which nobody reads and which wraps a Reddit
 * link over four lines. Only the text is shortened: the link still opens the full url.
 */
object LinkText {
    /** Links up to this long stay whole, apart from their scheme and "www.". */
    const val MAX_LENGTH = 40

    /**
     * A first path part is cut rather than dropped while this much of it still fits: a site and a
     * few letters say more than a site and a bare "/…".
     */
    private const val MIN_PART = 8

    private val prefix = Regex("^(https?://)?(www\\.)?", RegexOption.IGNORE_CASE)

    fun shorten(url: String, max: Int = MAX_LENGTH): String {
        val bare = url.replaceFirst(prefix, "").trimEnd('/')
        if (bare.length <= max) return bare
        val hostEnd = bare.indexOfAny(charArrayOf('/', '?', '#'))
        // A site is kept whole however long it is: it is the one part that must not be guessed.
        if (hostEnd < 0) return bare
        val host = bare.substring(0, hostEnd)
        val rest = bare.substring(hostEnd)
        val path = rest.substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotEmpty() }

        var out = host
        var kept = 0
        for (part in parts) {
            // Room left for the "/…" that says something follows.
            if (out.length + 1 + part.length + 2 > max) break
            out += "/$part"
            kept++
        }
        // Everything of the path fitted, so what went was the query or the fragment.
        if (kept == parts.size) return "$out…"
        if (kept == 0) {
            val room = max - host.length - 2
            if (room >= MIN_PART) return "$host/${parts[0].take(room)}…"
        }
        return "$out/…"
    }
}
