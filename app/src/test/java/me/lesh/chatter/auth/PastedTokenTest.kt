package me.lesh.chatter.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PastedTokenTest {
    @Test
    fun aBareTokenIsTakenAsItIs() {
        assertEquals("abc123", PastedToken.read("abc123"))
    }

    @Test
    fun spaceAroundItIsDropped() {
        assertEquals("abc123", PastedToken.read("  abc123\n"))
    }

    @Test
    fun theIrcFormLosesItsPrefix() {
        assertEquals("abc123", PastedToken.read("oauth:abc123"))
    }

    @Test
    fun aWholeRedirectUrlGivesItsToken() {
        assertEquals("abc123", PastedToken.read("https://example.com/token#access_token=abc123&scope=chat%3Aread&state=s"))
    }

    @Test
    fun theWebsitesAppLinkGivesItsToken() {
        assertEquals("abc123", PastedToken.read("chatter://login?token=abc123"))
    }

    @Test
    fun emptyOrBrokenTextIsNoToken() {
        assertNull(PastedToken.read(""))
        assertNull(PastedToken.read("   "))
        assertNull(PastedToken.read("oauth:"))
        assertNull(PastedToken.read("abc 123"))
        assertNull(PastedToken.read("https://example.com/token"))
    }
}
