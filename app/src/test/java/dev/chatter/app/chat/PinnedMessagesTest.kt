package dev.chatter.app.chat

import dev.chatter.app.net.HelixFragment
import dev.chatter.app.net.HelixFragmentEmote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedMessagesTest {
    private fun text(t: String) = HelixFragment("text", t)
    private fun emote(name: String, id: String) = HelixFragment("emote", name, HelixFragmentEmote(id))

    @Test
    fun fragmentsBecomeTheEmotesTagOfIrc() {
        val fragments = listOf(text("Welcome! "), emote("bleedPurple", "62835"), text(" Type !rules"))
        assertEquals("62835:9-19", emotesTag(fragments))
    }

    @Test
    fun anEmoteUsedTwiceListsBothPlaces() {
        val fragments = listOf(emote("Kappa", "25"), text(" and "), emote("Kappa", "25"), text(" "), emote("LUL", "425618"))
        assertEquals("25:0-4,10-14/425618:16-18", emotesTag(fragments))
    }

    @Test
    fun positionsCountCodePointsLikeIrc() {
        // The emoji is two chars in Kotlin but one position on Twitch.
        val fragments = listOf(text("😀 "), emote("Kappa", "25"))
        assertEquals("25:2-6", emotesTag(fragments))
    }

    @Test
    fun aMessageWithoutEmotesHasNoTag() {
        assertNull(emotesTag(listOf(text("hello"), HelixFragment("mention", "@someone"))))
    }

    @Test
    fun theTagReadsBackToTheSameEmotes() {
        val fragments = listOf(text("😀 "), emote("Kappa", "25"), text(" hi"))
        val message = fragments.joinToString("") { it.text }
        val builder = MessageBuilder(
            object : EmoteSource {
                override fun lookup(channelId: String?, word: String) = null
                override fun lookupOwnTwitch(channelId: String?, word: String) = null
            },
            { _, _, _ -> emptyList() },
        )
        val ranges = builder.twitchEmotes(message, emotesTag(fragments))
        assertEquals(listOf("Kappa"), ranges.map { it.name })
    }
}
