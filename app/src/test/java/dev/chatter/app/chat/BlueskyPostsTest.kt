package dev.chatter.app.chat

import dev.chatter.app.net.AppJson
import dev.chatter.app.net.BskyThreadResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueskyPostsTest {
    private fun key(url: String) = PostLinks.find(url, setOf(PostSite.Bluesky))?.key

    private fun parse(json: String) = AppJson.decodeFromString<BskyThreadResponse>(json).thread?.post?.let(BlueskyPosts::post)

    /** Shortened from a real answer of public.api.bsky.app. */
    private val photoPost = """
        {"thread":{"${'$'}type":"app.bsky.feed.defs#threadViewPost","post":{
          "uri":"at://did:plc:z72i7hdynmk6r22z27h6tvur/app.bsky.feed.post/3mxu3hsnebc2v",
          "author":{"did":"did:plc:z72i7hdynmk6r22z27h6tvur","handle":"bsky.app","displayName":"Bluesky",
            "avatar":"https://cdn.bsky.app/img/avatar/plain/did:plc:z72i7hdynmk6r22z27h6tvur/bafkreihw"},
          "record":{"${'$'}type":"app.bsky.feed.post","createdAt":"2026-10-14T17:35:30.592Z","text":"saw some cool clouds today"},
          "embed":{"${'$'}type":"app.bsky.embed.images#view","images":[
            {"thumb":"https://cdn.bsky.app/img/feed_thumbnail/plain/did:plc:z72i7hdynmk6r22z27h6tvur/bafkreidv",
             "fullsize":"https://cdn.bsky.app/img/feed_fullsize/plain/did:plc:z72i7hdynmk6r22z27h6tvur/bafkreidv",
             "alt":"clouds","aspectRatio":{"height":1600,"width":1200}}]},
          "replyCount":218,"repostCount":388,"likeCount":7284,"quoteCount":44,"labels":[]}}}
    """.trimIndent()

    @Test
    fun aPostOnBlueskyIsItsAuthorAndRecordKey() {
        assertEquals("bsky.app/3mxu3hsnebc2v", key("https://bsky.app/profile/bsky.app/post/3mxu3hsnebc2v"))
        assertEquals(
            "did:plc:z72i7hdynmk6r22z27h6tvur/3mxu3hsnebc2v",
            key("https://bsky.app/profile/did:plc:z72i7hdynmk6r22z27h6tvur/post/3mxu3hsnebc2v"),
        )
        assertEquals("jay.bsky.team/3mxu3hsnebc2v", key("https://fxbsky.app/profile/jay.bsky.team/post/3mxu3hsnebc2v"))
    }

    @Test
    fun everythingElseOnBlueskyStaysALink() {
        assertNull(key("https://bsky.app/profile/bsky.app"))
        assertNull(key("https://bsky.app/profile/bsky.app/lists/3mxu3hsnebc2v"))
        assertNull(key("https://bsky.app/search?q=profile/bsky.app/post/3mxu3hsnebc2v"))
    }

    @Test
    fun onlyWhatTheProtocolAllowsGoesIntoTheQuery() {
        assertNull(key("https://bsky.app/profile/not-a-handle/post/3mxu3hsnebc2v"))
        assertNull(key("https://bsky.app/profile/did:evil:x/post/3mxu3hsnebc2v"))
        assertNull(key("https://bsky.app/profile/bsky.app/post/a%20b"))
        assertNull(key("https://bsky.app/profile/bsky.app/post/.."))
        assertNull(key("https://bsky.app/profile/bsky.app%2Fapp.bsky.feed.post%2Fx/post/3mxu3hsnebc2v"))
    }

    @Test
    fun aPostWithAPictureReadsAsItsCard() {
        val post = parse(photoPost)!!
        assertEquals("Bluesky", post.name)
        assertEquals("bsky.app", post.handle)
        assertEquals("saw some cool clouds today", post.text)
        assertEquals(388L, post.reposts)
        assertEquals(1791999330592L, post.postedAt)
        assertFalse(post.nsfw)
        val media = post.media!!
        assertFalse(media.video)
        assertEquals(1200f / 1600f, media.aspectRatio!!, 0.001f)
        assertTrue(media.pictureUrl.startsWith("https://cdn.bsky.app/img/feed_thumbnail/"))
    }

    @Test
    fun aVideoShowsItsStill() {
        val post = parse(
            """{"thread":{"post":{"author":{"handle":"a.bsky.social"},"record":{"text":"hi"},
              "embed":{"${'$'}type":"app.bsky.embed.video#view","thumbnail":"https://video.bsky.app/watch/x/thumbnail.jpg",
                "aspectRatio":{"width":1826,"height":1078}}}}}""",
        )!!
        assertTrue(post.media!!.video)
        assertEquals("https://video.bsky.app/watch/x/thumbnail.jpg", post.media!!.pictureUrl)
    }

    @Test
    fun picturesNextToAQuoteAreThePostsOwn() {
        val post = parse(
            """{"thread":{"post":{"author":{"handle":"a.bsky.social"},"record":{"text":"hi"},
              "embed":{"${'$'}type":"app.bsky.embed.recordWithMedia#view","media":{"${'$'}type":"app.bsky.embed.images#view",
                "images":[{"thumb":"https://cdn.bsky.app/a"},{"thumb":"https://cdn.bsky.app/b"}]}}}}}""",
        )!!
        assertEquals("https://cdn.bsky.app/a", post.media!!.pictureUrl)
        assertEquals(1, post.media!!.more)
    }

    @Test
    fun aLabelledPostIsNsfw() {
        val post = parse(
            """{"thread":{"post":{"author":{"handle":"a.bsky.social"},"record":{"text":"hi"},
              "labels":[{"src":"did:plc:x","val":"porn"}]}}}""",
        )!!
        assertTrue(post.nsfw)
        assertFalse(parse("""{"thread":{"post":{"author":{"handle":"a.bsky.social"},"labels":[{"val":"!no-unauthenticated"}]}}}""")!!.nsfw)
    }

    @Test
    fun picturesAreOnlyFetchedFromBluesky() {
        val post = parse(
            """{"thread":{"post":{"author":{"handle":"a.bsky.social","avatar":"https://evil.example/a.jpg"},
              "embed":{"${'$'}type":"app.bsky.embed.images#view","images":[{"thumb":"https://bsky.app.evil.example/b.jpg"}]}}}}""",
        )!!
        assertNull(post.avatarUrl)
        assertNull(post.media)
    }

    @Test
    fun aMissingOrBlockedPostIsNoCard() {
        assertNull(parse("""{"thread":{"${'$'}type":"app.bsky.feed.defs#blockedPost","blocked":true}}"""))
        assertNull(parse("""{"thread":{"post":{"record":{"text":"hi"}}}}"""))
    }

    @Test
    fun withoutADateTheCardHasNone() {
        assertEquals(0L, parse("""{"thread":{"post":{"author":{"handle":"a.bsky.social"},"record":{"createdAt":"soon"}}}}""")!!.postedAt)
    }
}
