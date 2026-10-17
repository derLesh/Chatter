package dev.chatter.app.chat

/** A message's line without the links and GIFs drawn below it, and those in order. */
class PulledLinks(
    val rest: List<Segment>,
    val images: List<String>,
    val posts: List<PostLink>,
    val gifs: List<Segment.Gif> = emptyList(),
)

/** Takes the links that are shown as a picture or a post card, and the GIFs, out of a message. */
object LinkPreviews {
    /** More posts in one message stay links; a wall of cards would push the chat off the screen. */
    const val MAX_POSTS = 2

    /**
     * Pictures on [imageHosts], posts on [postSites] and, with [gifs], GIFs, taken out of
     * [segments]. The space a removed link leaves goes with it.
     */
    fun pull(
        segments: List<Segment>,
        imageHosts: Collection<String>,
        postSites: Set<PostSite>,
        gifs: Boolean = false,
    ): PulledLinks {
        val images = ArrayList<String>(0)
        val posts = ArrayList<PostLink>(0)
        val pulledGifs = ArrayList<Segment.Gif>(0)
        fun take(seg: Segment): Boolean {
            if (seg is Segment.Gif) return gifs.also { if (it) pulledGifs += seg }
            if (seg !is Segment.Link) return false
            ImageLinks.imageUrl(seg.url, imageHosts)?.let { images += it; return true }
            if (posts.size >= MAX_POSTS) return false
            PostLinks.find(seg.url, postSites)?.let { posts += it; return true }
            return false
        }
        if (segments.none { it is Segment.Link || it is Segment.Gif }) return PulledLinks(segments, images, posts)

        val rest = ArrayList<Segment>(segments.size)
        var dropped = false
        for (seg in segments) {
            if (take(seg)) {
                dropped = true
                continue
            }
            rest += if (dropped && seg is Segment.Text) Segment.Text(seg.text.trimStart(' ')) else seg
            dropped = false
        }
        if (images.isEmpty() && posts.isEmpty() && pulledGifs.isEmpty()) return PulledLinks(segments, images, posts)
        // A link at the end of the line takes the space before it.
        if (dropped) (rest.lastOrNull() as? Segment.Text)?.let { rest[rest.lastIndex] = Segment.Text(it.text.trimEnd(' ')) }
        return PulledLinks(rest.filterNot { it is Segment.Text && it.text.isEmpty() }, images, posts, pulledGifs)
    }
}
