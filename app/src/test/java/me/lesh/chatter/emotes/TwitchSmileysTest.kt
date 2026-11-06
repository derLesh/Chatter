package me.lesh.chatter.emotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TwitchSmileysTest {
    @Test
    fun helixPatternsBecomeWhatPeopleType() {
        assertEquals(":)", TwitchSmileys.nameOf("""\:-?\)"""))
        assertEquals(":P", TwitchSmileys.nameOf("""\:-?(p|P)"""))
        assertEquals(":/", TwitchSmileys.nameOf("""\:-?[\\/]"""))
        assertEquals(":|", TwitchSmileys.nameOf("""\:-?[z|Z|\|]"""))
        assertEquals("<3", TwitchSmileys.nameOf("""\&lt\;3"""))
        assertEquals(">(", TwitchSmileys.nameOf("""\&gt\;\("""))
        assertEquals("O_o", TwitchSmileys.nameOf("""[oO](_|\.)[oO]"""))
        assertEquals("Kappa", TwitchSmileys.nameOf("Kappa"))
    }

    @Test
    fun everyWayTwitchAcceptsFindsTheSmiley() {
        assertEquals(":)", TwitchSmileys.typedBy(":)"))
        assertEquals(":)", TwitchSmileys.typedBy(":-)"))
        assertEquals(":P", TwitchSmileys.typedBy(":p"))
        assertEquals(":/", TwitchSmileys.typedBy(":\\"))
        assertEquals(":|", TwitchSmileys.typedBy(":Z"))
        assertEquals("O_o", TwitchSmileys.typedBy("o.O"))
        assertNull(TwitchSmileys.typedBy(":"))
        assertNull(TwitchSmileys.typedBy(":-()"))
        assertNull(TwitchSmileys.typedBy("Kappa"))
    }
}
