package dev.chatter.app.chat

import dev.chatter.app.net.AppJson
import dev.chatter.app.net.FxTweetResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XPostsTest {
    private fun parse(json: String) = AppJson.decodeFromString<FxTweetResponse>(json).tweet?.let(XPosts::post)

    private fun key(url: String) = PostLinks.find(url, setOf(PostSite.X))?.key

    @Test
    fun aPostOnXUnderAnyOfItsNamesIsItsId() {
        assertEquals("20", key("https://x.com/jack/status/20"))
        assertEquals("20", key("https://twitter.com/jack/status/20"))
        assertEquals("20", key("https://mobile.twitter.com/jack/status/20"))
        assertEquals("20", key("https://www.x.com/jack/status/20?s=46&t=abc"))
        assertEquals("20", key("https://fxtwitter.com/jack/status/20"))
        assertEquals("20", key("https://vxtwitter.com/jack/status/20"))
        assertEquals("20", key("https://fixupx.com/jack/status/20"))
        assertEquals("20", key("https://x.com/i/web/status/20"))
        assertEquals("1585341984679469056", key("https://x.com/elonmusk/status/1585341984679469056/video/1"))
    }

    @Test
    fun everythingElseOnXStaysALink() {
        assertNull(key("https://x.com/jack"))
        assertNull(key("https://x.com/home"))
        assertNull(key("https://x.com/search?q=status/20"))
        assertNull(key("https://x.com/a/b/status/20"))
        assertNull(key("https://x.com/jack/status/"))
    }

    @Test
    fun onlyDigitsBecomeAnId() {
        assertNull(key("https://x.com/jack/status/20abc"))
        assertNull(key("https://x.com/jack/status/..%2F..%2Fadmin"))
        assertNull(key("https://x.com/jack/status/123456789012345678901"))
    }

    /** Shortened from a real answer of api.fxtwitter.com. */
    private val photoPost = """
        {"code":200,"message":"OK","tweet":{"url":"https://x.com/BarackObama/status/266031293945503744",
        "id":"266031293945503744","text":"Four more years. http://t.co/bAJE6Vom",
        "raw_text":{"text":"Four more years. http://t.co/bAJE6Vom","display_text_range":[0,37],
          "facets":[{"type":"media","indices":[17,37],"original":"http://t.co/bAJE6Vom"}]},
        "author":{"screen_name":"BarackObama","name":"Barack Obama",
          "avatar_url":"https://pbs.twimg.com/profile_images/1/a_200x200.jpg"},
        "replies":12000,"retweets":800000,"likes":1500000,"created_timestamp":1352261778,
        "possibly_sensitive":false,
        "media":{"all":[{"type":"photo","url":"https://pbs.twimg.com/media/A7EiDWcCYAAZT1D.jpg?name=orig","width":800,"height":532}]}}}
    """.trimIndent()

    @Test
    fun aPostWithAPictureReadsAsItsCard() {
        val tweet = parse(photoPost)!!
        assertEquals("Barack Obama", tweet.name)
        assertEquals("BarackObama", tweet.handle)
        assertEquals("https://pbs.twimg.com/profile_images/1/a_200x200.jpg", tweet.avatarUrl)
        assertEquals(800000L, tweet.reposts)
        assertEquals(1352261778000L, tweet.postedAt)
        assertFalse(tweet.nsfw)
        val media = tweet.media!!
        assertFalse(media.video)
        assertEquals(0, media.more)
        assertEquals(800f / 532f, media.aspectRatio!!, 0.001f)
    }

    @Test
    fun theAttachmentsLinkIsNotPartOfTheText() {
        assertEquals("Four more years.", parse(photoPost)!!.text)
    }

    @Test
    fun aPictureIsFetchedSmall() {
        assertEquals("https://pbs.twimg.com/media/A7EiDWcCYAAZT1D.jpg?name=small", parse(photoPost)!!.media!!.pictureUrl)
    }

    @Test
    fun aVideoShowsItsStill() {
        val tweet = parse(
            """{"tweet":{"text":"hi","author":{"screen_name":"a"},"possibly_sensitive":true,"media":{"all":[
              {"type":"video","url":"https://video.twimg.com/v.mp4","thumbnail_url":"https://pbs.twimg.com/thumb.jpg"},
              {"type":"photo","url":"https://pbs.twimg.com/media/b.jpg"}]}}}""",
        )!!
        assertTrue(tweet.nsfw)
        assertTrue(tweet.media!!.video)
        assertEquals("https://pbs.twimg.com/thumb.jpg", tweet.media!!.pictureUrl)
        assertEquals(1, tweet.media!!.more)
        assertNull(tweet.media!!.aspectRatio)
    }

    @Test
    fun picturesAreOnlyFetchedFromX() {
        val tweet = parse(
            """{"tweet":{"text":"hi","author":{"screen_name":"a","avatar_url":"https://evil.example/a.jpg"},
              "media":{"all":[{"type":"photo","url":"https://pbs.twimg.com.evil.example/b.jpg"}]}}}""",
        )!!
        assertNull(tweet.avatarUrl)
        assertNull(tweet.media)
        assertNull(parse("""{"tweet":{"author":{"screen_name":"a","avatar_url":"http://pbs.twimg.com/a.jpg"}}}""")!!.avatarUrl)
    }

    @Test
    fun aPostWithoutAnAuthorIsNoCard() {
        assertNull(parse("""{"code":404,"message":"NOT_FOUND"}"""))
        assertNull(parse("""{"tweet":{"text":"hi"}}"""))
    }
}
