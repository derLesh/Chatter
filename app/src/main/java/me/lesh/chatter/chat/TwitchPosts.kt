package me.lesh.chatter.chat

import me.lesh.chatter.net.HelixApi
import me.lesh.chatter.net.HelixClip
import me.lesh.chatter.net.HelixStream
import me.lesh.chatter.net.HelixUser
import me.lesh.chatter.net.HelixVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * Channels, clips and past broadcasts on Twitch: which links are one, and how Helix's answer becomes
 * a [Post]. A key is "channel/<login>", "clip/<slug>" or "video/<id>".
 */
object TwitchPosts {
    private val HOSTS = setOf("twitch.tv", "m.twitch.tv")
    private const val CLIPS_HOST = "clips.twitch.tv"

    /** Profile pictures, stream and video stills: static-cdn.jtvnw.net. */
    private const val PICTURE_HOST = "jtvnw.net"

    /** Where older clips keep their still. */
    private const val CLIP_PICTURE_HOST = "clips-media-assets2.twitch.tv"

    internal const val CHANNEL = "channel"
    internal const val CLIP = "clip"
    internal const val VIDEO = "video"

    private val LOGIN = Regex("[A-Za-z0-9_]{1,25}")
    private val SLUG = Regex("[A-Za-z0-9_-]{1,100}")
    private val VIDEO_ID = Regex("[0-9]{1,20}")

    /** The path segment before a video's id after the channel, as in /<channel>/v/<id>. */
    private val CHANNEL_VIDEO_PATHS = setOf("video", "v")

    /** Pages of twitch.tv itself, whose one path segment looks like a channel's name. */
    private val NOT_CHANNELS = setOf(
        "about", "bits", "broadcast", "directory", "downloads", "drops", "embed", "following", "friends",
        "inventory", "jobs", "login", "logout", "messages", "moderator", "p", "partners", "popout", "prime",
        "search", "settings", "signup", "store", "subscriptions", "turbo", "videos", "wallet",
    )

    private const val STILL_WIDTH = "640"
    private const val STILL_HEIGHT = "360"
    private const val STILL_ASPECT = 16f / 9f

    private val VIDEO_DURATION = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")

    /** The key of what [url] links to. Only what Twitch allows in each, since it goes into a query built here. */
    fun key(url: HttpUrl): String? {
        val host = url.host.removePrefix("www.")
        val segments = PostLinks.segments(url)
        if (host == CLIPS_HOST) {
            val slug = if (segments.firstOrNull() == "embed") url.queryParameter("clip") else segments.singleOrNull()
            return slug?.takeIf { SLUG.matches(it) }?.let { "$CLIP/$it" }
        }
        if (host !in HOSTS) return null
        val (kind, id) = when {
            segments.size == 1 -> CHANNEL to segments[0].lowercase().takeIf { it !in NOT_CHANNELS }
            segments.size == 2 && segments[0] == "videos" -> VIDEO to segments[1]
            segments.size == 2 && segments[0] == CLIP -> CLIP to segments[1]
            segments.size != 3 || !LOGIN.matches(segments[0]) -> return null
            segments[1] in CHANNEL_VIDEO_PATHS -> VIDEO to segments[2]
            segments[1] == CLIP -> CLIP to segments[2]
            else -> return null
        }
        val valid = when (kind) {
            VIDEO -> VIDEO_ID
            CLIP -> SLUG
            else -> LOGIN
        }
        return id?.takeIf { valid.matches(it) }?.let { "$kind/$it" }
    }

    fun isChannel(key: String) = key.startsWith("$CHANNEL/")

    /** What kind of link [key] is, and the name, slug or number it carries. */
    internal fun split(key: String): Pair<String, String>? {
        val kind = key.substringBefore('/', "")
        val id = key.substringAfter('/', "")
        return if (kind.isEmpty() || id.isEmpty()) null else kind to id
    }

    /**
     * A channel's card: the stream's title, viewers and category while it is [live], its description
     * otherwise. No date, since when the channel was made says nothing about it.
     */
    fun channel(user: HelixUser, live: HelixStream?, followers: Long) = Post(
        name = user.displayName.ifBlank { user.login },
        // Only where the display name is not the login in other letters, like a Japanese name.
        handle = user.login.takeUnless { it.equals(user.displayName, ignoreCase = true) }.orEmpty(),
        avatarUrl = picture(user.profileImageUrl),
        text = (live?.title ?: user.description).trim(),
        replies = 0,
        reposts = 0,
        likes = 0,
        viewers = live?.viewerCount?.toLong() ?: 0,
        followers = followers,
        category = live?.gameName.orEmpty(),
        profile = true,
        live = live != null,
        postedAt = 0,
        nsfw = false,
        media = null,
    )

    fun clip(clip: HelixClip, avatarUrl: String?) = video(
        label = PostLabel.Clip,
        channel = clip.broadcasterName,
        avatarUrl = avatarUrl,
        title = clip.title,
        views = clip.viewCount,
        createdAt = clip.createdAt,
        still = picture(clip.thumbnailUrl) ?: PostLinks.picture(clip.thumbnailUrl, CLIP_PICTURE_HOST)?.toString(),
        lengthSeconds = clip.duration.roundToInt(),
    )

    fun video(video: HelixVideo, avatarUrl: String?) = video(
        label = when (video.type) {
            "highlight" -> PostLabel.Highlight
            "upload" -> PostLabel.Upload
            else -> PostLabel.Vod
        },
        channel = video.userName,
        avatarUrl = avatarUrl,
        title = video.title,
        views = video.viewCount,
        createdAt = video.createdAt,
        // Empty, or a "processing" picture on another host, while the stream is still on.
        still = picture(video.thumbnailUrl.replace("%{width}", STILL_WIDTH).replace("%{height}", STILL_HEIGHT)),
        lengthSeconds = videoSeconds(video.duration),
    )

    private fun video(
        label: PostLabel,
        channel: String,
        avatarUrl: String?,
        title: String,
        views: Long,
        createdAt: String,
        still: String?,
        lengthSeconds: Int,
    ) = Post(
        name = channel.ifBlank { "Twitch" },
        handle = "",
        avatarUrl = avatarUrl,
        text = title.trim(),
        replies = 0,
        reposts = 0,
        likes = 0,
        views = views,
        label = label,
        postedAt = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrDefault(0),
        nsfw = false,
        media = still?.let { PostMedia(it, video = true, aspectRatio = STILL_ASPECT, more = 0, lengthSeconds = lengthSeconds) },
    )

    /** [raw] if it is a picture on Twitch's servers. */
    fun picture(raw: String?): String? = PostLinks.picture(raw, PICTURE_HOST)?.toString()

    /** "3h8m33s" in seconds; 0 for anything else. */
    internal fun videoSeconds(duration: String): Int {
        if (duration.isEmpty()) return 0
        val match = VIDEO_DURATION.matchEntire(duration) ?: return 0
        val (hours, minutes, seconds) = match.destructured
        return (hours.toIntOrNull() ?: 0) * 3600 + (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)
    }
}

/**
 * Read from Helix with the user's login, as everything else on Twitch is; a guest's links stay
 * links. A channel is asked again after a few minutes, so a card does not stay live for good.
 */
class TwitchPostSource(private val helix: HelixApi) : PostSource {
    override val site = PostSite.Twitch

    override fun freshFor(key: String): Long = if (TwitchPosts.isChannel(key)) CHANNEL_FRESH_MS else Long.MAX_VALUE

    override suspend fun fetch(key: String): Post? {
        val (kind, id) = TwitchPosts.split(key) ?: return null
        return when (kind) {
            TwitchPosts.CHANNEL -> channel(id)
            TwitchPosts.CLIP -> helix.clip(id)?.let { TwitchPosts.clip(it, avatar(it.broadcasterId)) }
            TwitchPosts.VIDEO -> helix.video(id)?.let { TwitchPosts.video(it, avatar(it.userId)) }
            else -> null
        }
    }

    private suspend fun channel(login: String): Post? = coroutineScope {
        val stream = async { helix.liveStreams(listOf(login)).firstOrNull() }
        val user = helix.users(listOf(login)).firstOrNull()
        if (user == null) {
            stream.cancel()
            return@coroutineScope null
        }
        val followers = optional { helix.followerCount(user.id) } ?: 0
        TwitchPosts.channel(user, stream.await(), followers)
    }

    private suspend fun avatar(userId: String): String? =
        if (userId.isEmpty()) null else optional { TwitchPosts.picture(helix.usersById(listOf(userId)).firstOrNull()?.profileImageUrl) }

    /** A card without its picture or follower count is still worth showing. */
    private suspend fun <T> optional(ask: suspend () -> T): T? = try {
        ask()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val CHANNEL_FRESH_MS = 5 * 60 * 1000L
    }
}
