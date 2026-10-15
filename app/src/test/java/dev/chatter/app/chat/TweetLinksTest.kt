package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TweetLinksTest {
    private fun link(url: String) = Segment.Link(url, url)

    @Test
    fun aPostOnXUnderAnyOfItsNamesIsItsId() {
        assertEquals("20", TweetLinks.tweetId("https://x.com/jack/status/20"))
        assertEquals("20", TweetLinks.tweetId("https://twitter.com/jack/status/20"))
        assertEquals("20", TweetLinks.tweetId("https://mobile.twitter.com/jack/status/20"))
        assertEquals("20", TweetLinks.tweetId("https://www.x.com/jack/status/20?s=46&t=abc"))
        assertEquals("20", TweetLinks.tweetId("https://fxtwitter.com/jack/status/20"))
        assertEquals("20", TweetLinks.tweetId("https://vxtwitter.com/jack/status/20"))
        assertEquals("20", TweetLinks.tweetId("https://fixupx.com/jack/status/20"))
        assertEquals("20", TweetLinks.tweetId("https://x.com/i/web/status/20"))
        assertEquals("1585341984679469056", TweetLinks.tweetId("https://x.com/elonmusk/status/1585341984679469056/video/1"))
    }

    @Test
    fun everythingElseOnXStaysALink() {
        assertNull(TweetLinks.tweetId("https://x.com/jack"))
        assertNull(TweetLinks.tweetId("https://x.com/home"))
        assertNull(TweetLinks.tweetId("https://x.com/search?q=status/20"))
        assertNull(TweetLinks.tweetId("https://x.com/a/b/status/20"))
        assertNull(TweetLinks.tweetId("https://x.com/jack/status/"))
    }

    @Test
    fun onlyDigitsBecomeAnId() {
        assertNull(TweetLinks.tweetId("https://x.com/jack/status/20abc"))
        assertNull(TweetLinks.tweetId("https://x.com/jack/status/..%2F..%2Fadmin"))
        assertNull(TweetLinks.tweetId("https://x.com/jack/status/123456789012345678901"))
    }

    @Test
    fun aHostDressedUpAsXIsStillItself() {
        assertNull(TweetLinks.tweetId("https://x.com.evil.example/jack/status/20"))
        assertNull(TweetLinks.tweetId("https://evil.example/x.com/jack/status/20"))
        assertNull(TweetLinks.tweetId("https://x.com@evil.example/jack/status/20"))
        assertNull(TweetLinks.tweetId("https://evil.example\\@x.com/jack/status/20"))
        assertNull(TweetLinks.tweetId("https://user@x.com/jack/status/20"))
        assertNull(TweetLinks.tweetId("javascript:alert(1)//x.com/jack/status/20"))
    }

    @Test
    fun aPostIsTakenOutOfTheLineWithItsSpace() {
        val pulled = LinkPreviews.pull(
            listOf(Segment.Text("look "), link("https://x.com/jack/status/20"), Segment.Text(" lol")),
            imageHosts = emptyList(),
            tweets = true,
        )
        assertEquals(listOf(Segment.Text("look "), Segment.Text("lol")), pulled.rest)
        assertEquals(listOf(TweetLink("20", "https://x.com/jack/status/20")), pulled.tweets)
    }

    @Test
    fun postsStayLinksWhileTheCardsAreOff() {
        val segments = listOf(Segment.Text("look "), link("https://x.com/jack/status/20"))
        val pulled = LinkPreviews.pull(segments, imageHosts = emptyList(), tweets = false)
        assertEquals(segments, pulled.rest)
        assertEquals(emptyList<TweetLink>(), pulled.tweets)
    }

    @Test
    fun aWallOfPostsKeepsTheRestAsLinks() {
        val links = (1..4).map { link("https://x.com/jack/status/$it") }
        val pulled = LinkPreviews.pull(links, imageHosts = emptyList(), tweets = true)
        assertEquals(listOf("1", "2"), pulled.tweets.map { it.id })
        assertEquals(links.drop(LinkPreviews.MAX_TWEETS), pulled.rest)
    }

    @Test
    fun picturesAndPostsInOneMessageAreBothTakenOut() {
        val pulled = LinkPreviews.pull(
            listOf(link("https://i.imgur.com/a.png"), Segment.Text(" "), link("https://x.com/jack/status/20")),
            imageHosts = ImageLinks.DEFAULT_HOSTS,
            tweets = true,
        )
        assertEquals(emptyList<Segment>(), pulled.rest)
        assertEquals(listOf("https://i.imgur.com/a.png"), pulled.images)
        assertEquals(listOf("20"), pulled.tweets.map { it.id })
    }
}
