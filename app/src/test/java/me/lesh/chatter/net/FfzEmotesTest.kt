package me.lesh.chatter.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FFZ's emote lists, read the way the app reads them. */
class FfzEmotesTest {
    @Test
    fun anEffectEmoteCarriesItsFlags() {
        val set = AppJson.decodeFromString<FfzSet>(
            """{"emoticons":[{"id":720508,"name":"ffzX","height":32,"width":32,"public":true,"hidden":false,"modifier":true,"modifier_flags":3,"urls":{"1":"https://cdn.frankerfacez.com/emote/720508/1"}},{"id":9,"name":"LUL","height":28,"width":28,"urls":{"1":"https://cdn.frankerfacez.com/emote/9/1"}}]}""",
        )
        val (flip, plain) = set.emoticons
        assertTrue(flip.modifier)
        assertEquals(3, flip.modifierFlags)
        assertFalse(plain.modifier)
        assertEquals(0, plain.modifierFlags)
    }
}
