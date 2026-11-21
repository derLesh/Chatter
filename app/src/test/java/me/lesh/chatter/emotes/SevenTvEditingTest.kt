package me.lesh.chatter.emotes

import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.SevenTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenTvEditingTest {
    /** Shortened from 7TV's answer to the channel query in SevenTvApi. */
    private val channel = AppJson.decodeFromString<SevenTvChannel>(
        """
        {"id":"OWNER","style":{"activeEmoteSetId":"SET"},"editors":[
          {"editorId":"SET_EDITOR","state":"ACCEPTED","permissions":{"emoteSet":{"manage":true}},
           "editor":{"connections":[{"platform":"KICK","platformId":"111"},{"platform":"TWITCH","platformId":"222"}]}},
          {"editorId":"PROFILE_EDITOR","state":"ACCEPTED","permissions":{"emoteSet":{"manage":false}},
           "editor":{"connections":[{"platform":"TWITCH","platformId":"333"}]}},
          {"editorId":"INVITED","state":"PENDING","permissions":{"emoteSet":{"manage":true}},
           "editor":{"connections":[{"platform":"TWITCH","platformId":"444"}]}},
          {"editorId":"KICK_ONLY","state":"ACCEPTED","permissions":{"emoteSet":{"manage":true}},
           "editor":{"connections":[{"platform":"KICK","platformId":"555"}]}}]}
        """.trimIndent(),
    )

    private fun may(twitchUserId: String, sevenTvUserId: String? = null) = mayEditEmotes(channel, CHANNEL_ID, twitchUserId, sevenTvUserId)

    @Test
    fun theAnswerIsRead() {
        assertEquals("SET", channel.style?.activeEmoteSetId)
        assertEquals(4, channel.editors.size)
    }

    @Test
    fun theStreamerMayAddEmotes() {
        assertTrue(may(CHANNEL_ID))
        assertTrue(may("999", sevenTvUserId = "OWNER"))
    }

    @Test
    fun anEditorOfTheEmoteSetsIsKnownByTheirTwitchAccount() {
        assertTrue(may("222"))
    }

    @Test
    fun anEditorIsKnownByThe7tvLoginToo() {
        assertTrue(may("999", sevenTvUserId = "KICK_ONLY"))
        assertFalse(may("555"))
    }

    @Test
    fun anEditorWithoutRightsToTheSetsOrNotYetAcceptedMayNot() {
        assertFalse(may("333"))
        assertFalse(may("444"))
        assertFalse(may("999", sevenTvUserId = "INVITED"))
    }

    @Test
    fun aStrangerMayNot() {
        assertFalse(may("999"))
        assertFalse(mayEditEmotes(SevenTvChannel(), CHANNEL_ID, "999", null))
    }

    private companion object {
        const val CHANNEL_ID = "100"
    }
}
