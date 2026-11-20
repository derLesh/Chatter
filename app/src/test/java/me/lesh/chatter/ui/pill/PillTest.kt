package me.lesh.chatter.ui.pill

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PillTest {
    @Test
    fun `the name put into a message is set in bold`() {
        val content = PillContent.filled("xqc", PillTone.Plain) { "$it removed" }
        assertEquals("xqc removed", content.text)
        val bold = highlighted(content.text, content.highlight).spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals(0 until 3, bold.start until bold.end)
    }

    @Test
    fun `the bold lands on the name, not on the same word before it`() {
        val content = PillContent.filled("to", PillTone.Success) { "Added to $it" }
        assertEquals("Added to to", content.text)
        assertEquals(9..10, content.highlight)
    }

    @Test
    fun `a text without the name stays plain`() {
        val content = PillContent.filled("xqc", PillTone.Error) { "Not connected to chat." }
        assertEquals("Not connected to chat.", content.text)
        assertNull(content.highlight)
        assertTrue(highlighted("Not connected to chat.", null).spanStyles.isEmpty())
        assertTrue(highlighted("Not connected to chat.", 0 until 0).spanStyles.isEmpty())
        assertTrue(highlighted("short", 3..20).spanStyles.isEmpty())
    }

    @Test
    fun `only problems vibrate`() {
        val problems = PillTone.entries.filter { it.isProblem }
        assertEquals(listOf(PillTone.Warning, PillTone.Error, PillTone.Offline), problems)
        assertFalse(PillTone.Success.isProblem)
    }
}
