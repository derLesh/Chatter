package dev.chatter.app.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A link to a post on X, shown as a card below its message. */
data class TweetLink(val id: String, val url: String)

/** Which links point to a post on X, and which post. */
object TweetLinks {
    /** X under its old and new name, and the embed fixers people paste so Discord shows a preview. */
    private val HOSTS = setOf("x.com", "twitter.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com")

    /** Post ids are 64-bit numbers. */
    private const val MAX_ID = 20

    /**
     * The id of the post [url] links to, or null. Only digits are accepted, since the id goes into
     * an address built here.
     */
    fun tweetId(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.host.removePrefix("www.").removePrefix("mobile.") !in HOSTS) return null
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }
        // <user>/status/<id> or i/web/status/<id>; a trailing /photo/1 is still the same post.
        val status = segments.indexOfFirst { it == "status" || it == "statuses" }
        val atUser = status == 1
        val atWeb = status == 2 && segments[0] == "i" && segments[1] == "web"
        if (!atUser && !atWeb) return null
        return segments.getOrNull(status + 1)?.takeIf { id -> id.length in 1..MAX_ID && id.all { it in '0'..'9' } }
    }
}
