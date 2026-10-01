package dev.chatter.app.chat

import java.net.IDN

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

    /** The marks and overrides that set which way text runs (Unicode's Bidi_Control). */
    private val BIDI = Regex("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** Scheme (optional), authority, and everything after it. */
    // A backslash ends the authority as a slash does: browsers and OkHttp read
    // `evil.example\@twitch.tv` as evil.example, so the text has to as well.
    private val parts = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)?([^/\\\\?#]*)(.*)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * [url] the way it is shown: the part before an "@" in front of the host left out, and the
     * host in punycode when it is not plain ASCII. Scam links are a constant in Twitch chats,
     * and both are how one dresses up as another site — `twitch.tv@evil.example` goes to
     * evil.example, and `twіtch.tv` with a Cyrillic "і" is not twitch.tv. The characters that turn
     * the direction of the text go as well: a U+202E in the path makes `evil.example/vt.hctiwt`
     * read as `evil.example/twitch.tv`. What is shown is where the link goes; everything else
     * stays as written.
     */
    fun honest(url: String): String {
        val plain = url.replace(BIDI, "")
        val (scheme, authority, rest) = split(plain) ?: return plain
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':')
        val port = hostPort.removePrefix(host)
        return scheme + ascii(host) + port + rest
    }

    /**
     * Whether [url] is one of the links [honest] has to correct, or one already in punycode —
     * worth a look at the real address before it is opened.
     */
    fun isUnusual(url: String): Boolean {
        val (_, authority, rest) = split(url) ?: return false
        val host = authority.substringAfterLast('@').substringBefore(':')
        return '@' in authority || rest.startsWith('\\') || BIDI.containsMatchIn(url) || host.any { it.code > 0x7F } ||
            host.split('.').any { it.startsWith("xn--", ignoreCase = true) }
    }

    /** How a link is written in the chat: [honest] always, and [shorten]ed when [short]. */
    fun display(url: String, short: Boolean): String = honest(url).let { if (short) shorten(it) else it }

    private fun split(url: String): Triple<String, String, String>? =
        parts.find(url)?.destructured?.let { (scheme, authority, rest) -> Triple(scheme, authority, rest) }

    private fun ascii(host: String): String =
        if (host.all { it.code <= 0x7F }) host
        else runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host)

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
