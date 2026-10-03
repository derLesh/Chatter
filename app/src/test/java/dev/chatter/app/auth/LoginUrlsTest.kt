package dev.chatter.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUrlsTest {
    @Test
    fun twitchsAnswerIsTheRedirect() {
        assertTrue(LoginUrls.isRedirect("http://localhost#access_token=abc&state=s"))
        assertTrue(LoginUrls.isRedirect("http://localhost/#access_token=abc&state=s"))
    }

    /** All of these start with "http://localhost", which a prefix check would accept. */
    @Test
    fun lookalikesAreNotTheRedirect() {
        assertFalse(LoginUrls.isRedirect("http://localhost.example.com/#access_token=abc"))
        assertFalse(LoginUrls.isRedirect("http://localhost:8080#access_token=abc"))
        assertFalse(LoginUrls.isRedirect("http://localhost@example.com/#access_token=abc"))
        assertFalse(LoginUrls.isRedirect("http://localhost/elsewhere#access_token=abc"))
        assertFalse(LoginUrls.isRedirect("https://localhost#access_token=abc"))
    }

    @Test
    fun theAnswerIsReadFromTheFragment() {
        val answer = LoginUrls.answer("http://localhost#access_token=abc&scope=chat%3Aread+chat%3Aedit&state=s1")
        assertEquals("abc", answer["access_token"])
        assertEquals("chat:read chat:edit", answer["scope"])
        assertEquals("s1", answer["state"])
    }

    /** The implicit flow never answers in the query, so a token there is not Twitch's. */
    @Test
    fun aQueryIsNotRead() {
        assertEquals(emptyMap<String, String>(), LoginUrls.answer("http://localhost?access_token=abc&state=s1"))
    }

    @Test
    fun twitchsOwnPagesStayInTheLogin() {
        assertTrue(LoginUrls.staysInLogin("https://id.twitch.tv/oauth2/authorize?client_id=x"))
        assertTrue(LoginUrls.staysInLogin("https://www.twitch.tv/login"))
        assertTrue(LoginUrls.staysInLogin("https://passport.twitch.tv/login"))
        assertTrue(LoginUrls.staysInLogin("https://twitch.tv/"))
    }

    @Test
    fun everythingElseLeavesTheLogin() {
        assertFalse(LoginUrls.staysInLogin("https://help.example.com/twitch.tv"))
        assertFalse(LoginUrls.staysInLogin("https://eviltwitch.tv/login"))
        assertFalse(LoginUrls.staysInLogin("https://twitch.tv.example.com/login"))
        assertFalse(LoginUrls.staysInLogin("https://www.twitch.tv@example.com/login"))
        assertFalse(LoginUrls.staysInLogin("http://www.twitch.tv/login"))
        assertFalse(LoginUrls.staysInLogin("intent://login#Intent;end"))
    }
}
