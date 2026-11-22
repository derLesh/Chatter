package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the log keeps out, so a shared log does not tell who sent it. */
class LogPrivacyTest {
    @Test
    fun aLinkKeepsItsHostOnly() {
        assertEquals(
            "History failed: https://recent-messages.robotty.de/… timed out",
            LogPrivacy.clean("History failed: https://recent-messages.robotty.de/api/v2/recent-messages/somechannel?limit=100 timed out"),
        )
        assertEquals("wss://irc-ws.chat.twitch.tv:443/…", LogPrivacy.clean("wss://irc-ws.chat.twitch.tv:443/?x=1"))
        assertEquals("Can't open Https://www.twitch.tv/…", LogPrivacy.clean("Can't open Https://www.twitch.tv/somebody"))
    }

    @Test
    fun aLinkWithoutPathStays() {
        assertEquals("Unable to resolve host \"api.twitch.tv\"", LogPrivacy.clean("Unable to resolve host \"api.twitch.tv\""))
        assertEquals("see https://7tv.io", LogPrivacy.clean("see https://7tv.io"))
    }

    @Test
    fun anErrorPageIsLeftOut() {
        assertEquals(
            "Channel badges failed: HTTP 400",
            LogPrivacy.clean("Channel badges failed: HTTP 400: {\"message\":\"user somebody is banned\"}"),
        )
    }

    @Test
    fun secretsEmailAndAddressesAreHidden() {
        val cleaned = LogPrivacy.clean(
            "Authorization: Bearer abc123 from me@example.com at 192.168.1.20 and 2001:db8:85a3:0:0:8a2e:370:7334",
        )
        listOf("abc123", "me@example.com", "192.168.1.20", "2001:db8").forEach {
            assertFalse("$it is hidden in: $cleaned", cleaned.contains(it))
        }
    }

    @Test
    fun shortenedIpv6AddressesAreHiddenToo() {
        for (address in listOf("2001:db8::1", "fe80::1c2b:3aff:fe4d:5e6f%wlan0", "::1", "::ffff:192.0.2.10", "2001:db8:0:0:1::")) {
            val cleaned = LogPrivacy.clean("Failed to connect to /$address (port 443)")
            assertEquals("$address in: $cleaned", "Failed to connect to /[hidden] (port 443)", cleaned)
        }
        assertEquals("Failed to connect to [[hidden]]:443", LogPrivacy.clean("Failed to connect to [2001:db8::42]:443"))
    }

    @Test
    fun codeAndTimesThatLookCloseStay() {
        for (text in listOf("Chat::onMessage", "at 12:30", "ratio 16:9:4")) {
            assertEquals(text, LogPrivacy.clean(text))
        }
    }

    @Test
    fun timesAndVersionsStay() {
        assertEquals("Trying again at 12:30:05 with 0.9.0", LogPrivacy.clean("Trying again at 12:30:05 with 0.9.0"))
    }
}
