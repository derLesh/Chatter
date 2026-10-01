package dev.chatter.app.net

/**
 * Where the pictures of emotes and badges may come from.
 *
 * Their addresses are not Chatter's own: FFZ, 7TV and Chatterino answer with a url, and Twitch's
 * badge list does too. Every chatter's client fetches them, so an address that pointed anywhere
 * else — a provider's mistake, or a provider that was broken into — would hand every user's IP
 * address to whoever runs that host, and could be as large a file as it likes. So a url from a
 * provider is used only when it is https on the provider's own hosts, the way a picture somebody
 * links in chat is only shown from the hosts the user allows (see ImageLinks).
 */
object TrustedImages {
    /** Matched with their subdomains, so "7tv.app" covers "cdn.7tv.app". */
    val HOSTS = listOf(
        // Twitch's CDN, for its badges and emotes.
        "jtvnw.net",
        "betterttv.net",
        "frankerfacez.com",
        "7tv.app", "7tv.io",
        // Where Chatterino keeps its badge pictures.
        "fourtf.com",
    )

    /**
     * [raw] as a url to fetch, or null when it is not one of [HOSTS]. A protocol-relative
     * "//cdn…" — FFZ and 7TV answer that way — becomes https.
     */
    fun url(raw: String): String? {
        val url = if (raw.startsWith("//")) "https:$raw" else raw
        if (!url.startsWith("https://", ignoreCase = true)) return null
        val authority = url.substring("https://".length).substringBefore('/').substringBefore('?').substringBefore('#')
        // "https://cdn.7tv.app@evil.example/" goes to evil.example.
        if ('@' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        return url.takeIf { HOSTS.any { host == it || host.endsWith(".$it") } }
    }
}
