package dev.chatter.app.chat

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
    )

    /**
     * The only file types fetched as pictures: raster formats Android decodes. Nothing that can
     * carry code (an SVG can contain scripts); anything else stays a link.
     */
    private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif")

    /** Longest id an Imgur or Gyazo link is expected to have. */
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
        // id. Albums and galleries stay links. The id goes into a URL built here, so only ASCII
        // letters and digits are accepted.
        val id = segments.singleOrNull()?.takeIf { it.length in 1..MAX_ID && it.all(::isPlainAscii) } ?: return null
        return when {
            host == "imgur.com" || host.endsWith(".imgur.com") -> "https://i.imgur.com/$id.png"
            host == "gyazo.com" || host.endsWith(".gyazo.com") -> "https://i.gyazo.com/$id.png"
            else -> null
        }
    }

    private fun isPlainAscii(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /**
     * Splits the picture links out of [segments]: returns the rest of the line and the pictures in
     * order. The space a removed link leaves goes with it.
     */
    fun split(segments: List<Segment>, hosts: Collection<String>): Pair<List<Segment>, List<String>> {
        if (segments.none { it is Segment.Link && imageUrl(it.url, hosts) != null }) return segments to emptyList()
        val rest = ArrayList<Segment>(segments.size)
        val urls = ArrayList<String>(2)
        var dropped = false
        for (seg in segments) {
            val image = (seg as? Segment.Link)?.let { imageUrl(it.url, hosts) }
            if (image != null) {
                urls += image
                dropped = true
                continue
            }
            rest += if (dropped && seg is Segment.Text) Segment.Text(seg.text.trimStart(' ')) else seg
            dropped = false
        }
        // A link at the end of the line takes the space before it.
        if (dropped) (rest.lastOrNull() as? Segment.Text)?.let { rest[rest.lastIndex] = Segment.Text(it.text.trimEnd(' ')) }
        return rest.filterNot { it is Segment.Text && it.text.isEmpty() } to urls
    }

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
}
