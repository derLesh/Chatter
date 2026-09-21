package dev.chatter.app.chat

/**
 * Which links in a message are shown as pictures, and where the picture actually is.
 *
 * Showing a link means fetching it, and the site it goes to is chosen by whoever wrote the
 * message. So nothing is fetched unless its host is on the user's list: [DEFAULT_HOSTS] is what
 * that list starts out as, and they can add to it or throw any of it away.
 */
object ImageLinks {
    /**
     * The image hosts a fresh install trusts — the ones people actually paste in Twitch chat.
     * They are matched with their subdomains too, so "imgur.com" covers "i.imgur.com".
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
     * The only file types that are ever fetched as a picture: the plain raster formats Android
     * knows how to decode. Nothing that could carry code of its own belongs here — an SVG is a
     * document with scripts in it, not a photo — and anything the app cannot name is a link
     * like any other.
     */
    private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif")

    /** The longest id an Imgur or Gyazo link is believed to carry. */
    private const val MAX_ID = 64

    /**
     * The picture [url] stands for, or null when it is not one this list allows.
     *
     * Everything has to line up: the link is plain http(s), its host is on [hosts], and it names
     * a file of a type in [IMAGE_TYPES]. Anything else — another scheme, another host, another
     * extension, a path this cannot make sense of — is left alone as a link, because the one
     * thing that must not happen is the app fetching, or building, an address somebody else
     * thought up.
     */
    fun imageUrl(url: String, hosts: Collection<String>): String? {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
        val host = host(url) ?: return null
        if (hosts.none { it.isNotEmpty() && (host == it || host.endsWith(".$it")) }) return null
        val path = path(url)
        if (path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in IMAGE_TYPES) return url
        // Imgur and Gyazo hand out a page, not the picture; the picture sits on their image host
        // under the same id. An album or a gallery is more than one picture, so it stays a link.
        // The id goes into a url this builds, so it may be nothing but plain ASCII letters and
        // digits — no dot, no slash, no escape, nothing that could steer the address elsewhere.
        val id = path.trim('/').takeIf { it.length in 1..MAX_ID && it.all(::isPlainAscii) } ?: return null
        return when {
            host == "imgur.com" || host.endsWith(".imgur.com") -> "https://i.imgur.com/$id.png"
            host == "gyazo.com" || host.endsWith(".gyazo.com") -> "https://i.gyazo.com/$id.png"
            else -> null
        }
    }

    private fun isPlainAscii(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /**
     * Takes every link that is a picture out of [segments], and hands back what is left of the
     * line together with the pictures, in the order they were written. The space such a link
     * leaves behind goes with it, so "look at <url> lol" does not keep a gap where the url was.
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
        // A url at the end of the line leaves its space behind the last word instead.
        if (dropped) (rest.lastOrNull() as? Segment.Text)?.let { rest[rest.lastIndex] = Segment.Text(it.text.trimEnd(' ')) }
        return rest.filterNot { it is Segment.Text && it.text.isEmpty() } to urls
    }

    /**
     * What the user typed, as a bare host: they may well paste a whole url, and "www." in front
     * of it would only make the entry match less than they meant.
     */
    fun cleanHost(typed: String): String = typed.trim()
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringAfterLast('@')
        .substringBefore(':')
        .removePrefix("www.")
        .lowercase()

    // The authority ends at the last "@", so "https://imgur.com@evil.example/x.png" is read as
    // the host it really goes to and not as the one it is dressed up as.
    private fun host(url: String): String? = url
        .substringAfter("://", "")
        .substringBefore('/')
        .substringAfterLast('@')
        .substringBefore(':')
        .lowercase()
        .takeIf { it.isNotEmpty() }

    private fun path(url: String): String =
        url.substringAfter("://", url).substringAfter('/', "").substringBefore('#').substringBefore('?')
}
