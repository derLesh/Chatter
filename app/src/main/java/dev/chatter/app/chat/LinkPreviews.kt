package dev.chatter.app.chat

/** A message's line without the links drawn below it, and those links in order. */
class PulledLinks(val rest: List<Segment>, val images: List<String>, val tweets: List<TweetLink>)

/** Takes the links that are shown as a picture or a post card out of a message. */
object LinkPreviews {
    /** More posts in one message stay links; a wall of cards would push the chat off the screen. */
    const val MAX_TWEETS = 2

    /**
     * Pictures on [imageHosts] and, if [tweets], posts on X, taken out of [segments]. The space a
     * removed link leaves goes with it.
     */
    fun pull(segments: List<Segment>, imageHosts: Collection<String>, tweets: Boolean): PulledLinks {
        val images = ArrayList<String>(0)
        val posts = ArrayList<TweetLink>(0)
        fun take(link: Segment.Link): Boolean {
            ImageLinks.imageUrl(link.url, imageHosts)?.let { images += it; return true }
            if (!tweets || posts.size >= MAX_TWEETS) return false
            TweetLinks.tweetId(link.url)?.let { posts += TweetLink(it, link.url); return true }
            return false
        }
        if (segments.none { it is Segment.Link }) return PulledLinks(segments, images, posts)

        val rest = ArrayList<Segment>(segments.size)
        var dropped = false
        for (seg in segments) {
            if (seg is Segment.Link && take(seg)) {
                dropped = true
                continue
            }
            rest += if (dropped && seg is Segment.Text) Segment.Text(seg.text.trimStart(' ')) else seg
            dropped = false
        }
        if (images.isEmpty() && posts.isEmpty()) return PulledLinks(segments, images, posts)
        // A link at the end of the line takes the space before it.
        if (dropped) (rest.lastOrNull() as? Segment.Text)?.let { rest[rest.lastIndex] = Segment.Text(it.text.trimEnd(' ')) }
        return PulledLinks(rest.filterNot { it is Segment.Text && it.text.isEmpty() }, images, posts)
    }
}
