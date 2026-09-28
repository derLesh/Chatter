package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTextTest {
    @Test
    fun aShortLinkLosesOnlyItsSchemeAndWww() {
        assertEquals("youtube.com/watch?v=6Ws4ydd85wU", LinkText.shorten("https://www.youtube.com/watch?v=6Ws4ydd85wU"))
        assertEquals("twitch.tv/lesh", LinkText.shorten("http://twitch.tv/lesh/"))
        assertEquals("twitch.tv/lesh", LinkText.shorten("WWW.twitch.tv/lesh"))
        assertEquals("example.com", LinkText.shorten("HTTPS://example.com/"))
    }

    @Test
    fun aLongPathKeepsWhatFitsOfItsStart() {
        assertEquals(
            "reddit.com/r/GamingLeaksAndRumours/…",
            LinkText.shorten("https://www.reddit.com/r/GamingLeaksAndRumours/comments/1wsrxo3/nintendo_switch_online_datamine_hints_at_new/"),
        )
    }

    @Test
    fun aLongQueryIsDroppedWhenThePathFits() {
        assertEquals(
            "example.com/article…",
            LinkText.shorten("https://example.com/article?utm_source=newsletter&utm_medium=email&utm_campaign=autumn"),
        )
        assertEquals("example.com/a/b…", LinkText.shorten("https://example.com/a/b#a-very-long-fragment-that-goes-on-and-on"))
    }

    @Test
    fun aLongFirstPartIsCutRatherThanDropped() {
        val short = LinkText.shorten("https://example.com/averyveryveryveryveryverylongsegmentname/x")
        assertEquals("example.com/averyveryveryveryveryverylo…", short)
        assertTrue(short.length <= LinkText.MAX_LENGTH)
    }

    @Test
    fun theSiteIsNeverCut() {
        val host = "a-site-with-a-name-far-longer-than-any-link-should-be.example.com"
        assertEquals(host, LinkText.shorten("https://$host"))
        assertEquals("$host/…", LinkText.shorten("https://$host/some/path"))
    }

    @Test
    fun nothingLongerThanTheLimitComesOutOfANormalLink() {
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghijklmnopqrstuvwxyz0123456789&index=3",
            "https://twitter.com/someone/status/1234567890123456789?s=20&t=abcdefghijklmnop",
            "https://clips.twitch.tv/SomeVeryLongClipSlugThatTwitchLikesToGive-AbCdEfGhIjKlMnOp",
        ).forEach { assertTrue(it, LinkText.shorten(it).length <= LinkText.MAX_LENGTH) }
    }
}
