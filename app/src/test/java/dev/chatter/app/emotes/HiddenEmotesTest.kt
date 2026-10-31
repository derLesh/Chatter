package dev.chatter.app.emotes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenEmotesTest {
    private val kekw = Emote("KEKW", "1", "https://cdn.7tv.app/emote/1/2x.webp", EmoteProvider.SevenTv)

    @Test
    fun anEmoteHiddenEverywhereIsHiddenInEveryChannel() {
        val hidden = HiddenEmotes(listOf(HiddenEmote(EmoteProvider.SevenTv, "1", "KEKW")))
        assertTrue(hidden.hides(kekw, "xqc"))
        assertTrue(hidden.hides(kekw, "forsen"))
    }

    @Test
    fun anEmoteHiddenInOneChannelShowsInTheOthers() {
        val hidden = HiddenEmotes(listOf(HiddenEmote(EmoteProvider.SevenTv, "1", "KEKW", channel = "xqc")))
        assertTrue(hidden.hides(kekw, "xqc"))
        assertFalse(hidden.hides(kekw, "forsen"))
    }

    /** Two channels can have different emotes of the same name. */
    @Test
    fun anotherEmoteOfTheSameNameStillShows() {
        val hidden = HiddenEmotes(listOf(HiddenEmote(EmoteProvider.SevenTv, "1", "KEKW")))
        assertFalse(hidden.hides(kekw.copy(id = "2"), "xqc"))
        assertFalse(hidden.hides(kekw.copy(provider = EmoteProvider.Bttv), "xqc"))
    }
}
