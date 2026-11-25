package me.lesh.chatter.chat

/** The message something was posted in, to name the poster and to jump back to it. */
data class PostedIn(
    val channel: String,
    val messageId: String,
    val login: String?,
    val displayName: String?,
    /** Milliseconds since the epoch. */
    val timestamp: Long,
)

/** A picture, GIF or video posted in a chat, as the channel page's media grid shows it. */
sealed interface SharedMedia {
    val url: String
    val postedIn: PostedIn

    /** A picture from a host on the user's list; [url] is the picture itself, see [ImageLinks.imageUrl]. */
    data class Image(override val url: String, override val postedIn: PostedIn) : SharedMedia

    /** [title] is the text it stands in for, "[Yes GIF by …]". */
    data class Gif(override val url: String, val title: String, override val postedIn: PostedIn) : SharedMedia

    /** A clip, VOD or YouTube video; its still comes with the post. */
    data class Video(val link: PostLink, override val postedIn: PostedIn) : SharedMedia {
        override val url: String get() = link.url
    }
}

/** Any other link posted in a chat. */
data class SharedLink(val url: String, val postedIn: PostedIn)

/** What a chat's messages shared, newest first, each address once. */
data class SharedItems(val media: List<SharedMedia>, val links: List<SharedLink>) {
    companion object {
        val EMPTY = SharedItems(emptyList(), emptyList())
    }
}

/**
 * Finds the pictures, GIFs, videos and other links in a chat's messages. What counts as media is
 * what the chat shows as a picture or video card: pictures on [imageHosts], GIFs with [gifs], and
 * videos on [postSites] that show videos. Everything else, a post card of X included, is a link.
 */
object SharedItemsFinder {
    /**
     * [messages] oldest first, as the chat holds them, with their bodies built. Deleted messages
     * are left out; an address posted twice keeps its newest message.
     */
    fun find(messages: List<ChatItem>, imageHosts: Collection<String>, postSites: Set<PostSite>, gifs: Boolean): SharedItems {
        val media = ArrayList<SharedMedia>()
        val links = ArrayList<SharedLink>()
        val seen = HashSet<String>()
        val videoSites = postSites.filterTo(HashSet()) { it.videos }
        for (item in messages.asReversed()) {
            if (item.deleted) continue
            val postedIn = PostedIn(item.channel, item.id, item.login, item.displayName, item.timestamp)
            for (segment in item.segments) {
                when (segment) {
                    is Segment.Gif -> if (gifs && seen.add(segment.url)) media += SharedMedia.Gif(segment.url, segment.title, postedIn)
                    is Segment.Link -> {
                        val image = ImageLinks.imageUrl(segment.url, imageHosts)
                        val video = if (image == null) PostLinks.find(segment.url, videoSites)?.takeIf(::isVideo) else null
                        when {
                            image != null -> if (seen.add(image)) media += SharedMedia.Image(image, postedIn)
                            video != null -> if (seen.add(segment.url)) media += SharedMedia.Video(video, postedIn)
                            seen.add(segment.url) -> links += SharedLink(segment.url, postedIn)
                        }
                    }
                    else -> {}
                }
            }
        }
        return SharedItems(media, links)
    }

    /** A link to a Twitch channel is a profile card, not a video. */
    private fun isVideo(link: PostLink): Boolean = !(link.site == PostSite.Twitch && TwitchPosts.isChannel(link.key))
}
