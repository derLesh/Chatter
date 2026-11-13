package me.lesh.chatter.debug

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a request went, as the log says it. */
class HttpLogTest {
    @Test
    fun twitchKeepsTheEndpointButNotTheQuery() {
        assertEquals("api.twitch.tv/helix/users", HttpLog.shown("https://api.twitch.tv/helix/users?login=somebody".toHttpUrl()))
    }

    @Test
    fun otherServicesShowOnlyTheirHost() {
        assertEquals("recent-messages.robotty.de", HttpLog.shown("https://recent-messages.robotty.de/api/v2/recent-messages/somebody".toHttpUrl()))
        assertEquals("7tv.io", HttpLog.shown("https://7tv.io/v3/users/twitch/12345".toHttpUrl()))
    }
}
