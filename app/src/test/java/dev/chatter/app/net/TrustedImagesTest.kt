package dev.chatter.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedImagesTest {
    /** What the providers actually send. */
    @Test
    fun theProvidersOwnHostsAreTrusted() {
        assertEquals("https://cdn.frankerfacez.com/emote/1/2", TrustedImages.url("//cdn.frankerfacez.com/emote/1/2"))
        assertEquals("https://cdn.7tv.app/emote/abc/2x.webp", TrustedImages.url("//cdn.7tv.app/emote/abc/2x.webp"))
        assertEquals("https://fourtf.com/chatterino/badges/dev.png", TrustedImages.url("https://fourtf.com/chatterino/badges/dev.png"))
        assertEquals(
            "https://static-cdn.jtvnw.net/badges/v1/abc/2",
            TrustedImages.url("https://static-cdn.jtvnw.net/badges/v1/abc/2"),
        )
    }

    @Test
    fun anyOtherHostIsNot() {
        assertNull(TrustedImages.url("https://evil.example/emote.png"))
        assertNull(TrustedImages.url("//evil.example/emote.png"))
        assertNull(TrustedImages.url("https://7tv.app.evil.example/x.webp"))
        assertNull(TrustedImages.url("https://evil7tv.app/x.webp"))
        assertNull(TrustedImages.url(""))
    }

    @Test
    fun onlyHttps() {
        assertNull(TrustedImages.url("http://cdn.7tv.app/emote/abc/2x.webp"))
        assertNull(TrustedImages.url("file:///data/data/dev.chatter.app/x"))
    }

    /** The authority ends at the last "@", so this goes to evil.example. */
    @Test
    fun userInfoCannotDressUpAHost() {
        assertNull(TrustedImages.url("https://cdn.7tv.app@evil.example/x.webp"))
        assertNull(TrustedImages.url("https://evil.example\\@cdn.7tv.app/x.webp"))
        assertNull(TrustedImages.url("//evil.example\\.7tv.app/x.webp"))
    }
}
