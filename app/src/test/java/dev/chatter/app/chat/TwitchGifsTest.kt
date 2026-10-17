package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchGifsTest {
    private val docsUrl = "https://media4.giphy.com/media/joSNxeswxuc74Juo8X/giphy.gif?" +
        "cid=095d7a5dzizsiwgabonagkmigggv8v1spfai91ac3x0dsiy0&ep=v1_gifs_trending&rid=giphy.gif&ct=g"

    @Test
    fun giphyUrlsAreUsedAsGiven() {
        assertEquals(docsUrl, TwitchGifs.url(docsUrl))
        assertEquals("https://giphy.com/x.gif", TwitchGifs.url("https://giphy.com/x.gif"))
    }

    @Test
    fun anyOtherUrlIsNot() {
        assertNull(TwitchGifs.url("http://media4.giphy.com/media/a/giphy.gif"))
        assertNull(TwitchGifs.url("https://evil.example/giphy.gif"))
        assertNull(TwitchGifs.url("https://giphy.com.evil.example/giphy.gif"))
        assertNull(TwitchGifs.url("https://evilgiphy.com/giphy.gif"))
        assertNull(TwitchGifs.url("https://media.giphy.com@evil.example/giphy.gif"))
        assertNull(TwitchGifs.url("https://evil.example\\@media.giphy.com/giphy.gif"))
        assertNull(TwitchGifs.url(""))
    }

    /** What would be fetched differs from what was checked and from what Twitch sent. */
    @Test
    fun aUrlThatChangesWhenParsedIsRefused() {
        assertNull(TwitchGifs.url("https://MEDIA.giphy.com/a.gif"))
        assertNull(TwitchGifs.url("https://media.giphy.com/a b.gif"))
    }

    @Test
    fun severalGifsInOneTag() {
        val tag = "0-4|a|https://media.giphy.com/a.gif,6-10|b|https://media.giphy.com/b.gif?x=1,2"
        assertEquals(
            listOf(
                TwitchGifs.Entry(0, 4, "https://media.giphy.com/a.gif"),
                TwitchGifs.Entry(6, 10, "https://media.giphy.com/b.gif?x=1,2"),
            ),
            TwitchGifs.parse(tag),
        )
    }

    @Test
    fun brokenEntriesAreSkipped() {
        assertTrue(TwitchGifs.parse("0-4|a").isEmpty())
        assertTrue(TwitchGifs.parse("x-4|a|https://media.giphy.com/a.gif").isEmpty())
        assertTrue(TwitchGifs.parse(null).isEmpty())
        assertEquals(1, TwitchGifs.parse("0-4|a|https://evil.example/a.gif,6-10|b|https://media.giphy.com/b.gif").size)
    }

    @Test
    fun gifsAreDrawnBelowTheLineOnlyWhenAsked() {
        val gif = Segment.Gif("[Yes GIF]", "https://media.giphy.com/a.gif")
        val segments = listOf(Segment.Text("look "), gif, Segment.Text(" nice"))

        val shown = LinkPreviews.pull(segments, emptyList(), emptySet(), gifs = true)
        assertEquals(listOf(gif), shown.gifs)
        assertEquals(listOf<Segment>(Segment.Text("look "), Segment.Text("nice")), shown.rest)

        val hidden = LinkPreviews.pull(segments, emptyList(), emptySet(), gifs = false)
        assertTrue(hidden.gifs.isEmpty())
        assertEquals(segments, hidden.rest)
    }
}
