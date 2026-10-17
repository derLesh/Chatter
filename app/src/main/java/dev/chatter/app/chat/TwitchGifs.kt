package dev.chatter.app.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Twitch's `gifs` tag: "0-33|joSNxeswxuc74Juo8X|https://media4.giphy.com/media/…/giphy.gif?cid=…",
 * one entry per GIF, separated by commas.
 */
object TwitchGifs {
    internal data class Entry(val startCp: Int, val endCp: Int, val url: String)

    /** Twitch sends Giphy's links only; a subdomain like media4 is picked per GIF. */
    private const val HOST = "giphy.com"

    /** Where the next entry begins, so a comma inside a URL does not split it. */
    private val NEXT_ENTRY = Regex(",(?=\\d+-\\d+\\|)")

    /** The entries of [tag] with an allowed URL, in code point positions as Twitch counts them. */
    internal fun parse(tag: String?): List<Entry> {
        if (tag.isNullOrEmpty()) return emptyList()
        return tag.split(NEXT_ENTRY).mapNotNull { entry ->
            val parts = entry.split('|', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val dash = parts[0].indexOf('-')
            if (dash <= 0) return@mapNotNull null
            val start = parts[0].substring(0, dash).toIntOrNull() ?: return@mapNotNull null
            val end = parts[0].substring(dash + 1).toIntOrNull() ?: return@mapNotNull null
            val url = url(parts[2]) ?: return@mapNotNull null
            Entry(start, end, url)
        }
    }

    /**
     * [raw] if it is https on Giphy and reads the same once parsed. Twitch asks that the URL is
     * used as given, and the parser that fetches it must see the host that was checked.
     */
    fun url(raw: String): String? {
        val parsed = raw.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val host = parsed.host
        if (host != HOST && !host.endsWith(".$HOST")) return null
        return raw.takeIf { parsed.toString() == raw }
    }
}
