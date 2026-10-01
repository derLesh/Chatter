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

class HonestLinkTest {
    /** The part before "@" is user info: the site is what follows it. */
    @Test
    fun userInfoInFrontOfTheHostIsLeftOut() {
        assertEquals("https://evil.example/login", LinkText.honest("https://twitch.tv@evil.example/login"))
        assertEquals("evil.example/login", LinkText.display("https://twitch.tv@evil.example/login", short = true))
        assertTrue(LinkText.isUnusual("https://twitch.tv@evil.example/login"))
    }

    /** "twіtch.tv" with a Cyrillic "і" looks exactly like twitch.tv, and is not. */
    @Test
    fun aHostThatIsNotPlainAsciiIsShownInPunycode() {
        val shown = LinkText.honest("https://twіtch.tv/lesh")
        assertTrue(shown, shown.startsWith("https://xn--"))
        assertTrue(shown.endsWith(".tv/lesh"))
        assertTrue(LinkText.isUnusual("https://twіtch.tv/lesh"))
        assertTrue(LinkText.isUnusual("https://xn--twtch-6ve.tv/"))
    }

    @Test
    fun anOrdinaryLinkIsLeftAsItIs() {
        val url = "https://www.twitch.tv/lesh?ref=chat#top"
        assertEquals(url, LinkText.honest(url))
        assertEquals("twitch.tv/lesh?ref=chat#top", LinkText.honest("twitch.tv/lesh?ref=chat#top"))
        assertTrue(!LinkText.isUnusual(url))
    }

    /** An "@" further on — a mention in a path, an address in a query — is not user info. */
    @Test
    fun anAtSignAfterTheHostIsNoTrick() {
        val url = "https://www.youtube.com/@lesh/videos?q=a@b"
        assertEquals(url, LinkText.honest(url))
        assertTrue(!LinkText.isUnusual(url))
    }

    /** A browser reads the backslash as a slash: this goes to evil.example, and says so. */
    @Test
    fun aBackslashEndsTheHost() {
        val url = "https://evil.example\\@twitch.tv/login"
        assertTrue(LinkText.honest(url).startsWith("https://evil.example"))
        assertTrue(LinkText.isUnusual(url))
    }

    @Test
    fun aPortStays() {
        assertEquals("http://evil.example:8080/x", LinkText.honest("http://user:pw@evil.example:8080/x"))
    }

    /** U+202E turns what follows around: written "vt.hctiwt", it reads "twitch.tv". */
    @Test
    fun charactersThatTurnTheTextAroundAreLeftOut() {
        val url = "https://evil.example/‮vt.hctiwt"
        assertEquals("https://evil.example/vt.hctiwt", LinkText.honest(url))
        assertEquals("evil.example/vt.hctiwt", LinkText.display(url, short = true))
        assertTrue(LinkText.isUnusual(url))
        assertTrue(LinkText.isUnusual("https://evil.example/⁧x⁩"))
    }
}
