package dev.chatter.app.emotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderEmotesTest {
    private fun emote(name: String, provider: EmoteProvider) =
        Emote(name, "$provider-$name", "https://example.invalid/$name", provider)

    private fun scope(vararg emotes: Emote) = ProviderEmotes(
        ffz = emotes.filter { it.provider == EmoteProvider.Ffz }.associateBy { it.name },
        bttv = emotes.filter { it.provider == EmoteProvider.Bttv }.associateBy { it.name },
        sevenTv = emotes.filter { it.provider == EmoteProvider.SevenTv }.associateBy { it.name },
    )

    @Test
    fun ffzWinsTheName() {
        val emotes = scope(
            emote("susge", EmoteProvider.SevenTv),
            emote("susge", EmoteProvider.Bttv),
            emote("susge", EmoteProvider.Ffz),
        )
        assertEquals(EmoteProvider.Ffz, emotes.byName["susge"]?.provider)
    }

    @Test
    fun bttvWinsOverSevenTv() {
        val emotes = scope(emote("susge", EmoteProvider.SevenTv), emote("susge", EmoteProvider.Bttv))
        assertEquals(EmoteProvider.Bttv, emotes.byName["susge"]?.provider)
    }

    @Test
    fun aNameOnlySevenTvHasStillResolves() {
        val emotes = scope(emote("catJAM", EmoteProvider.SevenTv), emote("susge", EmoteProvider.Bttv))
        assertEquals(EmoteProvider.SevenTv, emotes.byName["catJAM"]?.provider)
        assertEquals(setOf("catJAM", "susge"), emotes.byName.keys)
    }

    @Test
    fun aSevenTvAdditionDoesNotTakeABttvName() {
        val before = scope(emote("susge", EmoteProvider.Bttv))
        val after = before.withSevenTv(mapOf("susge" to emote("susge", EmoteProvider.SevenTv)))
        assertEquals(EmoteProvider.Bttv, after.byName["susge"]?.provider)
    }

    @Test
    fun aSevenTvRemovalLeavesTheBttvEmote() {
        val before = scope(emote("susge", EmoteProvider.Bttv), emote("susge", EmoteProvider.SevenTv))
        val after = before.withSevenTv(emptyMap())
        assertEquals(EmoteProvider.Bttv, after.byName["susge"]?.provider)
        assertNull(after.sevenTv["susge"])
    }
}
