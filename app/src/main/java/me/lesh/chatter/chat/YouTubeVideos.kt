package me.lesh.chatter.chat

import me.lesh.chatter.net.YouTubeApi
import me.lesh.chatter.net.YtOembed
import me.lesh.chatter.net.YtPlayerResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import java.time.OffsetDateTime
import java.util.Locale

/** Videos on YouTube: which links are one, and how YouTube's answer becomes a [Post]. */
object YouTubeVideos {
    private val HOSTS = setOf("youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com")
    private const val SHORT_HOST = "youtu.be"

    /** Where channel pictures are: yt3.ggpht.com. */
    private const val CHANNEL_PICTURE_HOST = "ggpht.com"

    /** The first path segment of a link that names its video in the second, like /shorts/<id>. */
    private val ID_PATHS = setOf("shorts", "live", "embed", "v", "e")

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** The video id [url] links to. Only what YouTube allows in one, since it goes into an address built here. */
    fun key(url: HttpUrl): String? {
        val host = url.host.removePrefix("www.")
        val segments = PostLinks.segments(url)
        val id = when {
            host == SHORT_HOST -> segments.firstOrNull()
            host !in HOSTS -> null
            segments.firstOrNull() == "watch" -> url.queryParameter("v")
            segments.size >= 2 && segments[0] in ID_PATHS -> segments[1]
            else -> null
        }
        return id?.takeIf { VIDEO_ID.matches(it) }
    }

    /** [raw] if it is a channel picture on YouTube's servers. */
    fun channelPicture(raw: String?): String? = PostLinks.picture(raw, CHANNEL_PICTURE_HOST)?.toString()

    /** The card for [player], or null if YouTube left out the video. */
    fun post(id: String, player: YtPlayerResponse): Post? {
        val details = player.videoDetails ?: return null
        if (details.title.isBlank()) return null
        val micro = player.microformat?.playerMicroformatRenderer
        return card(
            id, details.title, details.author,
            views = details.viewCount.toLongOrNull() ?: 0,
            postedAt = micro?.publishDate?.let(::epochMillis) ?: 0,
            nsfw = micro?.isFamilySafe == false,
            lengthSeconds = details.lengthSeconds.toIntOrNull() ?: 0,
        )
    }

    private fun epochMillis(date: String) = runCatching { OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull()

    /** The card for [oembed], which has no views, date or length. */
    fun post(id: String, oembed: YtOembed): Post? {
        if (oembed.title.isBlank()) return null
        return card(id, oembed.title, oembed.authorName, views = 0, postedAt = 0, nsfw = false, lengthSeconds = 0)
    }

    private fun card(id: String, title: String, channel: String, views: Long, postedAt: Long, nsfw: Boolean, lengthSeconds: Int) = Post(
        name = channel.ifBlank { "YouTube" },
        // A channel's @name is not in either answer, and the title says more.
        handle = "",
        avatarUrl = null,
        text = title.trim(),
        replies = 0,
        reposts = 0,
        likes = 0,
        views = views,
        postedAt = postedAt,
        nsfw = nsfw,
        media = PostMedia(
            // 480 × 360 with black bars, which the card's 16:9 crops off; every video has this one.
            pictureUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            video = true,
            aspectRatio = 16f / 9f,
            more = 0,
            lengthSeconds = lengthSeconds,
        ),
    )
}

/**
 * Read from the player YouTube's website asks; [YouTubeApi.oembed] stands in where it wants a
 * sign-in. The channel's picture is asked for at the same time, from the embedded player.
 */
class YouTubePostSource(private val api: YouTubeApi) : PostSource {
    override val site = PostSite.YouTube

    override suspend fun fetch(key: String): Post? = coroutineScope {
        val picture = async { channelPicture(key) }
        val post = video(key)
        if (post == null) {
            picture.cancel()
            return@coroutineScope null
        }
        post.copy(avatarUrl = picture.await())
    }

    private suspend fun video(key: String): Post? {
        val player = api.player(key, Locale.getDefault().language)
        YouTubeVideos.post(key, player)?.let { return it }
        if (player.playabilityStatus.status == "ERROR") return null
        // From a busy network YouTube asks to sign in before it says anything.
        return YouTubeVideos.post(key, api.oembed(key))
    }

    /** A card without the picture is still worth showing. */
    private suspend fun channelPicture(key: String): String? = try {
        YouTubeVideos.channelPicture(api.embedOverlay(key).channelPicture)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
