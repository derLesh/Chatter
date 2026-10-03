package dev.chatter.app.chat

import java.net.IDN

/**
 * How a link is written in a message: its site and the start of its path. The rest is ids and
 * tracking that make a Reddit link span four lines. Only the text is shortened; the link still
 * opens the full URL.
 */
object LinkText {
    /** Links up to this long stay whole, apart from their scheme and "www.". */
    const val MAX_LENGTH = 40

    /**
     * A first path part is cut instead of dropped while this much fits; a site and a few letters
     * say more than a bare "/…".
     */
    private const val MIN_PART = 8

    private val prefix = Regex("^(https?://)?(www\\.)?", RegexOption.IGNORE_CASE)

    /** Characters that set text direction (Unicode Bidi_Control). */
    private val BIDI = Regex("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** Scheme (optional), authority, and everything after it. */
    // A backslash ends the authority like a slash: browsers and OkHttp read
    // `evil.example\@twitch.tv` as evil.example, so the text must too.
    private val parts = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)?([^/\\\\?#]*)(.*)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * [url] as shown: anything before an "@" in front of the host removed, a non-ASCII host in
     * punycode, and direction control characters removed. These are how scam links pose as another
     * site: `twitch.tv@evil.example` goes to evil.example, `twіtch.tv` with a Cyrillic "і" is not
     * twitch.tv, and U+202E makes `evil.example/vt.hctiwt` read as `evil.example/twitch.tv`.
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
     * Whether [honest] has to correct [url], or it is already punycode; worth showing the real
     * address before opening it.
     */
    fun isUnusual(url: String): Boolean {
        val (_, authority, rest) = split(url) ?: return false
        val host = authority.substringAfterLast('@').substringBefore(':')
        return '@' in authority || rest.startsWith('\\') || BIDI.containsMatchIn(url) || host.any { it.code > 0x7F } ||
            host.split('.').any { it.startsWith("xn--", ignoreCase = true) }
    }

    /** How a link is written in the chat: always [honest], and [shorten]ed when [short]. */
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
        // The site is never shortened; it is the part that must not be guessed.
        if (hostEnd < 0) return bare
        val host = bare.substring(0, hostEnd)
        val rest = bare.substring(hostEnd)
        val path = rest.substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotEmpty() }

        var out = host
        var kept = 0
        for (part in parts) {
            // Leave room for the "/…".
            if (out.length + 1 + part.length + 2 > max) break
            out += "/$part"
            kept++
        }
        // The whole path fitted; only the query or fragment was cut.
        if (kept == parts.size) return "$out…"
        if (kept == 0) {
            val room = max - host.length - 2
            if (room >= MIN_PART) return "$host/${parts[0].take(room)}…"
        }
        return "$out/…"
    }
}
