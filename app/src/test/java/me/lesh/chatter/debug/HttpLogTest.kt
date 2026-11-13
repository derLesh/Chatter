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

    /** A delete key would let whoever reads the log delete the picture. */
    @Test
    fun theFullLinkLeavesOutKeys() {
        assertEquals("https://kappa.lol/api/delete?key=%E2%80%A6", HttpLog.full("https://kappa.lol/api/delete?key=s3cr3t".toHttpUrl()))
        assertEquals("https://7tv.io/v3/users?id=1", HttpLog.full("https://7tv.io/v3/users?id=1".toHttpUrl()))
        assertEquals("https://api.imgur.com/3/image/%E2%80%A6", HttpLog.full("https://api.imgur.com/3/image/d3l3t3h4sh".toHttpUrl()))
        assertEquals("https://api.imgur.com/3/image", HttpLog.full("https://api.imgur.com/3/image".toHttpUrl()))
    }

    @Test
    fun otherServicesShowOnlyTheirHost() {
        assertEquals("recent-messages.robotty.de", HttpLog.shown("https://recent-messages.robotty.de/api/v2/recent-messages/somebody".toHttpUrl()))
        assertEquals("7tv.io", HttpLog.shown("https://7tv.io/v3/users/twitch/12345".toHttpUrl()))
    }
}
