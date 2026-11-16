package me.lesh.chatter.chat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which links are shown as pictures, and where the picture is.
 *
 * The sender chooses the site, so only hosts on the user's list are fetched; [DEFAULT_HOSTS] is the
 * initial list.
 */
object ImageLinks {
    /**
     * Hosts a fresh install trusts, the ones commonly pasted in Twitch chat. Subdomains match too.
     */
    val DEFAULT_HOSTS = listOf(
        "imgur.com",
        "gyazo.com",
        "i.redd.it",
        "cdn.discordapp.com",
        "media.discordapp.net",
        "files.catbox.moe",
        "i.nuuls.com",
        "kappa.lol",
    )

    /** [DEFAULT_HOSTS] before the app noted which ones an install has been given. */
    val FIRST_DEFAULT_HOSTS = listOf(
        "imgur.com",
        "gyazo.com",
        "i.redd.it",
        "cdn.discordapp.com",
        "media.discordapp.net",
        "files.catbox.moe",
        "i.nuuls.com",
    )

    /**
     * [stored] plus the default hosts not in [given]: a host joins a list the user already has once,
     * and one they removed afterwards stays removed. An emptied list stays empty.
     */
    fun withNewDefaults(stored: List<String>, given: Collection<String>): List<String> =
        if (stored.isEmpty()) stored else stored + DEFAULT_HOSTS.filter { it !in given && it !in stored }

    /**
     * The only file types fetched as pictures: raster formats Android decodes. Nothing that can
     * carry code (an SVG can contain scripts); anything else stays a link.
     */
    private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif")

    /** Longest id an Imgur, Gyazo or kappa.lol link is expected to have. */
    private const val MAX_ID = 64

    /**
     * The picture [url] stands for, or null if not allowed: plain http(s), a host on [hosts], and a
     * file type in [IMAGE_TYPES]. Everything else stays a link, so the app never fetches or builds
     * an address someone else made up.
     */
    fun imageUrl(url: String, hosts: Collection<String>): String? {
        // Parsed with OkHttp, the client that fetches it, and the parsed URL is returned.
        // Hand-picking the host disagreed at the edges: a backslash ends the authority for OkHttp
        // as in browsers, so `https://evil.example\@imgur.com/a.png` looked like imgur.com but was
        // fetched from evil.example.
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val host = parsed.host
        if (hosts.none { it.isNotEmpty() && (host == it || host.endsWith(".$it")) }) return null
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }
        val file = segments.lastOrNull().orEmpty()
        if (file.substringAfterLast('.', "").lowercase() in IMAGE_TYPES) return parsed.toString()
        // Imgur and Gyazo links point to a page; the picture is on their image host under the same
        // id. kappa.lol serves the file itself, with or without a file type. Albums and galleries
        // stay links. The id goes into a URL built here, so only ASCII letters and digits pass.
        val id = segments.singleOrNull()?.takeIf { it.length in 1..MAX_ID && it.all(::isPlainAscii) } ?: return null
        return when {
            host == "imgur.com" || host.endsWith(".imgur.com") -> "https://i.imgur.com/$id.png"
            host == "gyazo.com" || host.endsWith(".gyazo.com") -> "https://i.gyazo.com/$id.png"
            host == "kappa.lol" || host.endsWith(".kappa.lol") -> "https://kappa.lol/$id"
            else -> null
        }
    }

    private fun isPlainAscii(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /**
     * Splits the picture links out of [segments]: returns the rest of the line and the pictures in
     * order. The space a removed link leaves goes with it.
     */
    fun split(segments: List<Segment>, hosts: Collection<String>): Pair<List<Segment>, List<String>> =
        LinkPreviews.pull(segments, hosts, postSites = emptySet()).let { it.rest to it.images }

    /**
     * What the user typed as a bare host; they may paste a whole URL, and a leading "www." would
     * match less than intended.
     */
    fun cleanHost(typed: String): String = typed.trim()
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringAfterLast('@')
        .substringBefore(':')
        .removePrefix("www.")
        .lowercase()

    /**
     * The site of the page at [pageUrl] to load images from, its host without a leading "www.",
     * or null where that site would cover much more than the page. Subdomains match too, so
     * `www.com` must not become `com`, nor a host on a public suffix like `co.uk` or `github.io`
     * stand for all of it; an IP address has no site at all. [siteOf] is OkHttp's lookup in the
     * public suffix list, which tests cannot load.
     */
    fun imageSourceOf(pageUrl: String, siteOf: (HttpUrl) -> String? = HttpUrl::topPrivateDomain): String? {
        val parsed = pageUrl.toHttpUrlOrNull() ?: return null
        // Null for a public suffix itself, a single label and an IP address.
        siteOf(parsed) ?: return null
        val withoutWww = parsed.host.removePrefix("www.")
        val keepsSite = withoutWww != parsed.host && "https://$withoutWww/".toHttpUrlOrNull()?.let(siteOf) != null
        return if (keepsSite) withoutWww else parsed.host
    }
}
