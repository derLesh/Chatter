package me.lesh.chatter.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenTvLoginTest {
    @Test
    fun theCallbackGivesItsCode() {
        val url = "https://7tv.app/login/callback?platform=twitch&code=abc%2B123&scope=user%3Aread%3Aemail"
        assertTrue(SevenTvLogin.isCallback(url))
        assertEquals("abc+123", SevenTvLogin.code(url))
    }

    @Test
    fun aDeclinedLoginHasNoCode() {
        assertNull(SevenTvLogin.code("https://7tv.app/login/callback?platform=twitch&error=access_denied&error_description=The+user+denied"))
        assertNull(SevenTvLogin.code("https://7tv.app/login/callback?platform=twitch&code="))
    }

    @Test
    fun onlyExactly7tvsCallbackCounts() {
        for (url in listOf(
            "http://7tv.app/login/callback?code=abc",
            "https://7tv.app.evil.example/login/callback?code=abc",
            "https://evil.example/login/callback?code=abc",
            "https://user@7tv.app/login/callback?code=abc",
            "https://7tv.app:8443/login/callback?code=abc",
            "https://7tv.app/login/callback/more?code=abc",
            "https://7tv.app/login?code=abc",
            "https://id.twitch.tv/oauth2/authorize?redirect_uri=https%3A%2F%2F7tv.app%2Flogin%2Fcallback",
        )) {
            assertFalse(url, SevenTvLogin.isCallback(url))
            assertNull(url, SevenTvLogin.code(url))
        }
    }
}
