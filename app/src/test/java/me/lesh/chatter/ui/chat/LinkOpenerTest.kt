package me.lesh.chatter.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which links a Custom Tab may show and which go straight to their app. */
class LinkOpenerTest {
    @Test
    fun webLinksOpenInATab() {
        assertTrue(isWebLink("https://example.com/page"))
        assertTrue(isWebLink("http://example.com"))
        assertTrue(isWebLink("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun otherSchemesGoToTheirApps() {
        assertFalse(isWebLink("mailto:someone@example.com"))
        assertFalse(isWebLink("tel:+49123"))
        assertFalse(isWebLink("market://details?id=me.lesh.chatter"))
        assertFalse(isWebLink("httpsfoo://example.com"))
    }

    @Test
    fun aCapitalisedSchemeIsWrittenSmall() {
        assertEquals("https://www.twitch.tv/Name", lowercaseScheme("Https://www.twitch.tv/Name"))
        assertEquals("mailto:Someone@example.com", lowercaseScheme("MAILTO:Someone@example.com"))
    }

    @Test
    fun textWithoutASchemeStaysAsItIs() {
        assertEquals("example.com/a:b", lowercaseScheme("example.com/a:b"))
        assertEquals("Hello world", lowercaseScheme("Hello world"))
        assertEquals(":x", lowercaseScheme(":x"))
    }
}
