package dev.chatter.app.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Hosts emote and badge pictures may be loaded from.
 *
 * The URLs come from the providers. One pointing elsewhere, by mistake or after a provider was
 * compromised, would hand every user's IP address to that host and could serve a file of any size.
 * So only https on the provider's own hosts is accepted, like linked images in chat (see
 * ImageLinks).
 */
object TrustedImages {
    /** Matched with their subdomains, so "7tv.app" covers "cdn.7tv.app". */
    val HOSTS = listOf(
        // Twitch's CDN, for its badges and emotes.
        "jtvnw.net",
        // Twitch's cheermotes; the host alone, since the rest of cloudfront.net is anybody's.
        "d3aqoihi2n8ty8.cloudfront.net",
        "betterttv.net",
        "frankerfacez.com",
        "7tv.app", "7tv.io",
        // Where Chatterino keeps its badge pictures.
        "fourtf.com",
    )

    /**
     * [raw] as a URL to fetch, or null if it is not on one of [HOSTS]. Protocol-relative "//cdn…"
     * (FFZ and 7TV) becomes https.
     */
    fun url(raw: String): String? {
        // Parsed and returned by the same parser that fetches it, so the checked host is the
        // requested one; see ImageLinks.imageUrl.
        val parsed = (if (raw.startsWith("//")) "https:$raw" else raw).toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val host = parsed.host
        return parsed.toString().takeIf { HOSTS.any { host == it || host.endsWith(".$it") } }
    }
}
