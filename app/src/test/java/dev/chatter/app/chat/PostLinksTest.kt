package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostLinksTest {
    private val all = PostSite.entries.toSet()

    private fun link(url: String) = Segment.Link(url, url)

    @Test
    fun aLinkIsFoundOnTheSiteItBelongsTo() {
        assertEquals(PostLink(PostSite.X, "20", "https://x.com/jack/status/20"), PostLinks.find("https://x.com/jack/status/20", all))
        assertEquals(
            PostLink(PostSite.Bluesky, "bsky.app/3mxu3hsnebc2v", "https://bsky.app/profile/bsky.app/post/3mxu3hsnebc2v"),
            PostLinks.find("https://bsky.app/profile/bsky.app/post/3mxu3hsnebc2v", all),
        )
    }

    @Test
    fun aSiteTurnedOffLeavesItsLinksAlone() {
        assertNull(PostLinks.find("https://x.com/jack/status/20", setOf(PostSite.Bluesky)))
        assertNull(PostLinks.find("https://bsky.app/profile/bsky.app/post/3mxu3hsnebc2v", setOf(PostSite.X)))
        assertNull(PostLinks.find("https://x.com/jack/status/20", emptySet()))
    }

    @Test
    fun aHostDressedUpAsASiteIsStillItself() {
        for (url in listOf(
            "https://x.com.evil.example/jack/status/20",
            "https://evil.example/x.com/jack/status/20",
            "https://x.com@evil.example/jack/status/20",
            "https://evil.example\\@x.com/jack/status/20",
            "https://user@x.com/jack/status/20",
            "javascript:alert(1)//x.com/jack/status/20",
            "https://bsky.app.evil.example/profile/bsky.app/post/3mxu3hsnebc2v",
            "https://user@bsky.app/profile/bsky.app/post/3mxu3hsnebc2v",
        )) assertNull(url, PostLinks.find(url, all))
    }

    @Test
    fun aPostIsTakenOutOfTheLineWithItsSpace() {
        val pulled = LinkPreviews.pull(
            listOf(Segment.Text("look "), link("https://x.com/jack/status/20"), Segment.Text(" lol")),
            imageHosts = emptyList(),
            postSites = all,
        )
        assertEquals(listOf(Segment.Text("look "), Segment.Text("lol")), pulled.rest)
        assertEquals(listOf(PostLink(PostSite.X, "20", "https://x.com/jack/status/20")), pulled.posts)
    }

    @Test
    fun postsStayLinksWhileTheCardsAreOff() {
        val segments = listOf(Segment.Text("look "), link("https://x.com/jack/status/20"))
        val pulled = LinkPreviews.pull(segments, imageHosts = emptyList(), postSites = emptySet())
        assertEquals(segments, pulled.rest)
        assertEquals(emptyList<PostLink>(), pulled.posts)
    }

    @Test
    fun aWallOfPostsKeepsTheRestAsLinks() {
        val links = listOf(
            link("https://x.com/jack/status/1"),
            link("https://bsky.app/profile/bsky.app/post/3mxu3hsnebc2v"),
            link("https://x.com/jack/status/3"),
        )
        val pulled = LinkPreviews.pull(links, imageHosts = emptyList(), postSites = all)
        assertEquals(listOf(PostSite.X, PostSite.Bluesky), pulled.posts.map { it.site })
        assertEquals(links.drop(LinkPreviews.MAX_POSTS), pulled.rest)
    }

    @Test
    fun picturesAndPostsInOneMessageAreBothTakenOut() {
        val pulled = LinkPreviews.pull(
            listOf(link("https://i.imgur.com/a.png"), Segment.Text(" "), link("https://x.com/jack/status/20")),
            imageHosts = ImageLinks.DEFAULT_HOSTS,
            postSites = all,
        )
        assertEquals(emptyList<Segment>(), pulled.rest)
        assertEquals(listOf("https://i.imgur.com/a.png"), pulled.images)
        assertEquals(listOf("20"), pulled.posts.map { it.key })
    }
}
