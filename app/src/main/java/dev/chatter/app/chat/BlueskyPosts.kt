package dev.chatter.app.chat

import dev.chatter.app.net.BlueskyApi
import dev.chatter.app.net.BskyAspectRatio
import dev.chatter.app.net.BskyEmbed
import dev.chatter.app.net.BskyPost
import okhttp3.HttpUrl
import java.time.OffsetDateTime

/** Posts on Bluesky: which links are one, and how the AppView's answer becomes a [Post]. */
object BlueskyPosts {
    /** Bluesky and the embed fixers people paste so Discord shows a preview. */
    private val HOSTS = setOf("bsky.app", "fxbsky.app", "bskx.app")

    /** Where the pictures are: cdn.bsky.app, and video.bsky.app for a video's still. */
    private const val IMAGE_HOST = "bsky.app"

    /** Labels that blur a picture, as Bluesky's own app does. */
    private val NSFW_LABELS = setOf("porn", "sexual", "nudity", "graphic-media", "gore")

    private val HANDLE = Regex("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+")
    private val DID = Regex("did:(plc:[a-z2-7]{24}|web:[a-zA-Z0-9.-]+)")
    private val RECORD_KEY = Regex("[a-zA-Z0-9._~-]{1,64}")

    /**
     * "<actor>/<record key>" for a link to profile/<handle or DID>/post/<record key>. Both go into a
     * query built here, so only what the AT Protocol allows in them is accepted.
     */
    fun key(url: HttpUrl): String? {
        if (url.host.removePrefix("www.") !in HOSTS) return null
        val segments = PostLinks.segments(url)
        if (segments.size < 4 || segments[0] != "profile" || segments[2] != "post") return null
        val actor = segments[1]
        val recordKey = segments[3]
        if (!HANDLE.matches(actor) && !DID.matches(actor)) return null
        if (!RECORD_KEY.matches(recordKey) || recordKey == "." || recordKey == "..") return null
        return "$actor/$recordKey"
    }

    /** The card for [view], or null without an author. */
    fun post(view: BskyPost): Post? {
        val handle = view.author.handle.ifBlank { return null }
        return Post(
            name = view.author.displayName?.takeIf { it.isNotBlank() } ?: handle,
            handle = handle,
            avatarUrl = PostLinks.picture(view.author.avatar, IMAGE_HOST)?.toString(),
            text = view.record.text.trim(),
            replies = view.replyCount,
            reposts = view.repostCount,
            likes = view.likeCount,
            postedAt = runCatching { OffsetDateTime.parse(view.record.createdAt).toInstant().toEpochMilli() }.getOrDefault(0),
            nsfw = view.labels.any { it.value in NSFW_LABELS },
            media = media(view.embed),
        )
    }

    private fun media(embed: BskyEmbed?): PostMedia? = when (embed?.type) {
        "app.bsky.embed.images#view" -> embed.images.firstOrNull()?.let { media(it.thumb, false, it.aspectRatio, embed.images.size - 1) }
        "app.bsky.embed.video#view" -> media(embed.thumbnail, true, embed.aspectRatio, 0)
        "app.bsky.embed.external#view" -> media(embed.external?.thumb, false, null, 0)
        "app.bsky.embed.recordWithMedia#view" -> media(embed.media)
        // A quoted post alone has no picture of this post's own.
        else -> null
    }

    private fun media(raw: String?, video: Boolean, ratio: BskyAspectRatio?, more: Int): PostMedia? {
        val picture = PostLinks.picture(raw, IMAGE_HOST) ?: return null
        return PostMedia(
            pictureUrl = picture.toString(),
            video = video,
            aspectRatio = ratio?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height },
            more = more,
        )
    }
}

/** Read from Bluesky's public AppView, the same place its own app asks. */
class BlueskyPostSource(private val api: BlueskyApi) : PostSource {
    override val site = PostSite.Bluesky

    override suspend fun fetch(key: String): Post? {
        val actor = key.substringBefore('/')
        val recordKey = key.substringAfter('/', "").ifEmpty { return null }
        return api.post(actor, recordKey).thread?.post?.let(BlueskyPosts::post)
    }
}
