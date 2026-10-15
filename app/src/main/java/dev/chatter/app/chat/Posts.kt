package dev.chatter.app.chat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A site whose links to a post are shown as a card. A new site is an entry here, which recognizes
 * its links, and a [PostSource], which fetches them.
 */
enum class PostSite {
    X {
        override fun key(url: HttpUrl) = XPosts.key(url)
    },
    Bluesky {
        override fun key(url: HttpUrl) = BlueskyPosts.key(url)
    },
    ;

    /** What [PostSource.fetch] needs to find the post [url] links to, or null if it is none. */
    abstract fun key(url: HttpUrl): String?
}

/** A link to a post, shown as a card below its message. */
data class PostLink(val site: PostSite, val key: String, val url: String)

/** A post as its card shows it, whichever site it is from. */
data class Post(
    val name: String,
    val handle: String,
    val avatarUrl: String?,
    val text: String,
    val replies: Long,
    val reposts: Long,
    val likes: Long,
    /** Milliseconds since the epoch. */
    val postedAt: Long,
    /** Marked NSFW by the site or its author; its picture is blurred if the user wants that. */
    val nsfw: Boolean,
    val media: PostMedia?,
)

/** The first picture of a post, or a still of its video. [more]: how many other attachments it has. */
data class PostMedia(val pictureUrl: String, val video: Boolean, val aspectRatio: Float?, val more: Int)

/** Reads the posts of one [site]. */
interface PostSource {
    val site: PostSite

    /** The post [key] stands for, or null if it is gone or private. Throws on a network failure. */
    suspend fun fetch(key: String): Post?
}

object PostLinks {
    /**
     * The post [url] links to on one of [sites], or null. Parsed with OkHttp, which fetches it, so
     * the host checked is the host asked; see [ImageLinks.imageUrl].
     */
    fun find(url: String, sites: Set<PostSite>): PostLink? {
        if (sites.isEmpty()) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return sites.firstNotNullOfOrNull { site -> site.key(parsed)?.let { PostLink(site, it, url) } }
    }

    /** The path of [url] without empty segments. */
    internal fun segments(url: HttpUrl) = url.pathSegments.filter { it.isNotEmpty() }

    /**
     * [raw] if it is https on [host] or one of its subdomains. A post's pictures come from its
     * site's servers; a changed API cannot make the app fetch from anywhere else.
     */
    internal fun picture(raw: String?, host: String): HttpUrl? {
        val parsed = raw?.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return parsed.takeIf { it.host == host || it.host.endsWith(".$host") }
    }
}
