package me.lesh.chatter.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelUpdateTest {
    @Test
    fun onlyWhatChangedIsSent() {
        assertEquals("""{"title":"Hello"}""", HelixApi.channelUpdate("Hello", null, null, null).toString())
    }

    @Test
    fun noTagsLeftIsAnEmptyListAndUntouchedTagsAreLeftOut() {
        assertEquals("""{"tags":[]}""", HelixApi.channelUpdate(null, null, emptyList(), null).toString())
        assertEquals("""{"game_id":"0"}""", HelixApi.channelUpdate(null, "0", null, null).toString())
    }

    @Test
    fun tagsAndLanguageGoUnderTwitchsNames() {
        assertEquals(
            """{"tags":["German","Chill"],"broadcaster_language":"de"}""",
            HelixApi.channelUpdate(null, null, listOf("German", "Chill"), "de").toString(),
        )
    }
}
