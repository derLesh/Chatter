package me.lesh.chatter.chat

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
    YouTube {
        override fun key(url: HttpUrl) = YouTubeVideos.key(url)
        override val videos get() = true
    },
    Twitch {
        override fun key(url: HttpUrl) = TwitchPosts.key(url)
        override val videos get() = true
    },
    SevenTv {
        override fun key(url: HttpUrl) = SevenTvEmotes.key(url)
    },
    ;

    /** What [PostSource.fetch] needs to find the post [url] links to, or null if it is none. */
    abstract fun key(url: HttpUrl): String?

    /** Its cards show videos: a title above who made it, its views and a still beside them. */
    open val videos: Boolean get() = false
}

/** A link to a post, shown as a card below its message. */
data class PostLink(val site: PostSite, val key: String, val url: String)

/** A post as its card shows it, whichever site it is from. */
data class Post(
    val name: String,
    /** Empty where the site has none to show, like a YouTube channel. */
    val handle: String,
    val avatarUrl: String?,
    val text: String,
    val replies: Long,
    val reposts: Long,
    val likes: Long,
    val views: Long = 0,
    /** Watching a live stream right now. */
    val viewers: Long = 0,
    val followers: Long = 0,
    /** What a live stream is playing or doing, like "Just Chatting"; empty for anything else. */
    val category: String = "",
    /** A channel rather than something it posted: its picture large on the left, no other picture. */
    val profile: Boolean = false,
    /** A channel streaming right now. */
    val live: Boolean = false,
    /** What kind of video it is, where the site has more than one, like a clip or a VOD on Twitch. */
    val label: PostLabel? = null,
    /** Milliseconds since the epoch; 0 where there is no date worth showing, like for a channel. */
    val postedAt: Long,
    /** Marked NSFW by the site or its author; its picture is blurred if the user wants that. */
    val nsfw: Boolean,
    val media: PostMedia?,
    /** An emote rather than a post: [name] is its name, and its card shows only this beside it. */
    val emote: PostEmote? = null,
)

/** A 7TV emote as its card shows it. [creator] is who uploaded it, empty where 7TV does not say. */
data class PostEmote(val id: String, val pictureUrl: String, val creator: String, val aspectRatio: Float)

/**
 * The first picture of a post, or a still of its video. [more]: how many other attachments it has.
 * [lengthSeconds]: how long the video runs, 0 where it is not known.
 */
data class PostMedia(val pictureUrl: String, val video: Boolean, val aspectRatio: Float?, val more: Int, val lengthSeconds: Int = 0)

/** The kinds of video Twitch has. */
enum class PostLabel { Clip, Vod, Highlight, Upload }

/** Reads the posts of one [site]. */
interface PostSource {
    val site: PostSite

    /** The post [key] stands for, or null if it is gone or private. Throws on a network failure. */
    suspend fun fetch(key: String): Post?

    /**
     * How long, in milliseconds, what [fetch] said about [key] holds. A post does not change enough
     * to ask twice; whether a channel is live does.
     */
    fun freshFor(key: String): Long = Long.MAX_VALUE
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
