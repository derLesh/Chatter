package me.lesh.chatter.ui.inbox

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightNameTest {
    private val style = SpanStyle(fontWeight = FontWeight.Bold)

    private fun highlighted(text: String, login: String = "lesh"): List<String> {
        val result = highlightName(text, login, style)
        return result.spanStyles.map { result.text.substring(it.start, it.end) }
    }

    @Test
    fun marksTheNameWithAndWithoutTheAt() {
        assertEquals(listOf("@lesh", "lesh"), highlighted("@lesh are you there? lesh!"))
    }

    @Test
    fun ignoresCase() {
        assertEquals(listOf("@LeSh"), highlighted("hi @LeSh"))
    }

    @Test
    fun leavesLongerNamesAlone() {
        assertEquals(emptyList<String>(), highlighted("@leshy and fleshy and lesh_fan"))
    }

    @Test
    fun keepsTheText() {
        assertEquals("gg @lesh", highlightName("gg @lesh", "lesh", style).text)
    }

    @Test
    fun marksNothingForAGuest() {
        assertEquals(emptyList<String>(), highlighted("lesh", login = ""))
    }

    @Test
    fun readsTheLoginAsPlainText() {
        assertEquals(listOf("a.b"), highlighted("a.b axb", login = "a.b"))
    }
}
