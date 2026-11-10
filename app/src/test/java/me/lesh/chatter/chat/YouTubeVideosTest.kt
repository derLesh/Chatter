package me.lesh.chatter.chat

import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.YtOembed
import me.lesh.chatter.net.YtPlayerResponse
import me.lesh.chatter.ui.chat.videoLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeVideosTest {
    private fun key(url: String) = PostLinks.find(url, setOf(PostSite.YouTube))?.key

    private fun player(json: String) = YouTubeVideos.post(ID, AppJson.decodeFromString<YtPlayerResponse>(json))

    @Test
    fun everyKindOfVideoLinkIsItsId() {
        for (url in listOf(
            "https://www.youtube.com/watch?v=$ID",
            "https://youtube.com/watch?v=$ID&t=42s",
            "https://m.youtube.com/watch?feature=share&v=$ID",
            "https://music.youtube.com/watch?v=$ID&list=RD$ID",
            "https://youtu.be/$ID",
            "https://youtu.be/$ID?si=abcdef&t=10",
            "https://www.youtube.com/shorts/$ID",
            "https://youtube.com/shorts/$ID?feature=share",
            "https://www.youtube.com/live/$ID",
            "https://www.youtube.com/embed/$ID",
            "https://www.youtube-nocookie.com/embed/$ID",
            "http://youtube.com/v/$ID",
        )) assertEquals(url, ID, key(url))
    }

    @Test
    fun everythingElseOnYouTubeStaysALink() {
        for (url in listOf(
            "https://www.youtube.com/",
            "https://www.youtube.com/@RickAstleyYT",
            "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
            "https://www.youtube.com/playlist?list=PL1234567890",
            "https://www.youtube.com/results?search_query=$ID",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/shorts/",
            "https://youtu.be/",
        )) assertNull(url, key(url))
    }

    @Test
    fun onlyAnElevenCharacterIdIsAnId() {
        assertNull(key("https://www.youtube.com/watch?v=dQw4w9WgXc"))
        assertNull(key("https://www.youtube.com/watch?v=dQw4w9WgXcQQ"))
        assertNull(key("https://youtu.be/dQw4w9W%2F.Q"))
        assertNull(key("https://www.youtube.com/shorts/..%2F..%2Fabc"))
    }

    @Test
    fun aHostDressedUpAsYouTubeIsStillItself() {
        for (url in listOf(
            "https://youtube.com.evil.example/watch?v=$ID",
            "https://evil.example/youtube.com/watch?v=$ID",
            "https://youtube.com@evil.example/watch?v=$ID",
            "https://user@youtube.com/watch?v=$ID",
            "https://notyoutube.com/watch?v=$ID",
            "https://youtu.be.evil.example/$ID",
        )) assertNull(url, key(url))
    }

    /** Shortened from a real answer of youtubei/v1/player. */
    private val video = """
        {"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"},
        "videoDetails":{"videoId":"$ID","title":"Rick Astley - Never Gonna Give You Up (Official Video) ",
          "lengthSeconds":"213","channelId":"UCuAXFkgsw1L7xaCfnd5JJOw","viewCount":"1835626972",
          "author":"Rick Astley","isPrivate":false,"isLiveContent":false},
        "microformat":{"playerMicroformatRenderer":{"isFamilySafe":true,"viewCount":"1835626972",
          "publishDate":"2009-10-24T23:57:33-07:00","ownerChannelName":"Rick Astley"}}}
    """.trimIndent()

    @Test
    fun aVideoReadsAsItsCard() {
        val post = player(video)!!
        assertEquals("Rick Astley", post.name)
        assertEquals("", post.handle)
        assertEquals("Rick Astley - Never Gonna Give You Up (Official Video)", post.text)
        assertEquals(1835626972L, post.views)
        assertEquals(1256453853000L, post.postedAt)
        assertFalse(post.nsfw)
        val media = post.media!!
        assertTrue(media.video)
        assertEquals(213, media.lengthSeconds)
        assertEquals("https://i.ytimg.com/vi/$ID/hqdefault.jpg", media.pictureUrl)
    }

    @Test
    fun anAgeRestrictedVideoIsNsfw() {
        val json = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED"},
            "videoDetails":{"title":"Song","author":"Band","viewCount":"181263","lengthSeconds":"200"},
            "microformat":{"playerMicroformatRenderer":{"isFamilySafe":false}}}
        """.trimIndent()
        assertTrue(player(json)!!.nsfw)
    }

    @Test
    fun aLiveStreamHasNoLength() {
        val json = """{"videoDetails":{"title":"lofi radio","author":"Lofi Girl","viewCount":"651847116","lengthSeconds":"0","isLiveContent":true}}"""
        assertEquals(0, player(json)!!.media!!.lengthSeconds)
    }

    @Test
    fun aMissingVideoHasNoCard() {
        assertNull(player("""{"playabilityStatus":{"status":"ERROR","reason":"This video is unavailable"}}"""))
    }

    @Test
    fun oembedGivesTitleAndChannelOnly() {
        val json = """
            {"title":"Rick Astley - Never Gonna Give You Up","author_name":"Rick Astley",
            "author_url":"https://www.youtube.com/@RickAstleyYT","type":"video",
            "thumbnail_url":"https://i.ytimg.com/vi/$ID/hqdefault.jpg"}
        """.trimIndent()
        val post = YouTubeVideos.post(ID, AppJson.decodeFromString<YtOembed>(json))!!
        assertEquals("Rick Astley", post.name)
        assertEquals("Rick Astley - Never Gonna Give You Up", post.text)
        assertEquals(0L, post.views)
        assertEquals(0L, post.postedAt)
    }

    @Test
    fun theLengthIsWrittenAsYouTubeWritesIt() {
        assertEquals("0:07", videoLength(7))
        assertEquals("3:33", videoLength(213))
        assertEquals("1:02:03", videoLength(3723))
    }

    private companion object {
        const val ID = "dQw4w9WgXcQ"
    }
}
