package dev.chatter.app.chat

import dev.chatter.app.net.FxTweet
import dev.chatter.app.net.FxTwitterApi
import okhttp3.HttpUrl

/** Posts on X: which links are one, and how FxTwitter's answer becomes a [Post]. */
object XPosts {
    /** X under its old and new name, and the embed fixers people paste so Discord shows a preview. */
    private val HOSTS = setOf("x.com", "twitter.com", "fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com")

    /** Post ids are 64-bit numbers. */
    private const val MAX_ID = 20

    /** Where the pictures are. */
    private const val IMAGE_HOST = "twimg.com"

    /** The post id [url] links to. Only digits, since the id goes into an address built here. */
    fun key(url: HttpUrl): String? {
        if (url.host.removePrefix("www.").removePrefix("mobile.") !in HOSTS) return null
        val segments = PostLinks.segments(url)
        // <user>/status/<id> or i/web/status/<id>; a trailing /photo/1 is still the same post.
        val status = segments.indexOfFirst { it == "status" || it == "statuses" }
        val atUser = status == 1
        val atWeb = status == 2 && segments[0] == "i" && segments[1] == "web"
        if (!atUser && !atWeb) return null
        return segments.getOrNull(status + 1)?.takeIf { id -> id.length in 1..MAX_ID && id.all { it in '0'..'9' } }
    }

    /** The card for [fx], or null without an author. */
    fun post(fx: FxTweet): Post? {
        if (fx.author.screenName.isBlank()) return null
        // Older posts still end in the t.co link of their attachment, which the card shows.
        val attachmentLinks = fx.rawText?.facets.orEmpty().filter { it.type == "media" && it.original.isNotEmpty() }
        val text = attachmentLinks.fold(fx.text) { text, facet -> text.replace(facet.original, "") }.trim()
        return Post(
            name = fx.author.name.ifBlank { fx.author.screenName },
            handle = fx.author.screenName,
            avatarUrl = PostLinks.picture(fx.author.avatarUrl, IMAGE_HOST)?.toString(),
            text = text,
            replies = fx.replies,
            reposts = fx.retweets,
            likes = fx.likes,
            postedAt = fx.createdTimestamp * 1000,
            nsfw = fx.possiblySensitive,
            media = media(fx),
        )
    }

    private fun media(fx: FxTweet): PostMedia? {
        val all = fx.media?.all.orEmpty()
        val first = all.firstOrNull() ?: return null
        val video = first.type != "photo"
        val picture = if (video) PostLinks.picture(first.thumbnailUrl, IMAGE_HOST)
        // The original can be several megabytes; "small" is 680 pixels wide at most.
        else PostLinks.picture(first.url, IMAGE_HOST)?.newBuilder()?.setQueryParameter("name", "small")?.build()
        return PostMedia(
            pictureUrl = picture?.toString() ?: return null,
            video = video,
            aspectRatio = if (first.width > 0 && first.height > 0) first.width.toFloat() / first.height else null,
            more = all.size - 1,
        )
    }
}

/** X has no free API for a post, so it is read through FxTwitter. */
class XPostSource(private val api: FxTwitterApi) : PostSource {
    override val site = PostSite.X

    override suspend fun fetch(key: String): Post? = api.tweet(key).tweet?.let(XPosts::post)
}
