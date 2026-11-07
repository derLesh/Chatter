package me.lesh.chatter.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkedTokenTest {
    @Test
    fun theWebsiteLinkGivesItsToken() {
        assertEquals("abc123", LinkedToken.read("chatter://login?token=abc123"))
    }

    @Test
    fun otherParametersAreIgnored() {
        assertEquals("abc123", LinkedToken.read("chatter://login?from=web&token=abc123"))
    }

    @Test
    fun anotherSchemeOrHostIsNoLogin() {
        assertNull(LinkedToken.read("https://login?token=abc123"))
        assertNull(LinkedToken.read("chatter://channel?token=abc123"))
        assertNull(LinkedToken.read("chatter://login.example.com?token=abc123"))
    }

    @Test
    fun aMissingOrBrokenTokenIsNoLogin() {
        assertNull(LinkedToken.read(null))
        assertNull(LinkedToken.read("chatter://login"))
        assertNull(LinkedToken.read("chatter://login?token="))
        assertNull(LinkedToken.read("chatter://login?token=abc%20123"))
        assertNull(LinkedToken.read("chatter://login?mytoken=abc123"))
    }
}
