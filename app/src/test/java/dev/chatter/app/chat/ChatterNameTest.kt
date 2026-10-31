package dev.chatter.app.chat

import dev.chatter.app.settings.NameStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatterNameTest {
    private fun korean(style: NameStyle) = chatterName("다니엘", "daniel", style)
    private fun latin(style: NameStyle) = chatterName("Forsen", "forsen", style)

    @Test
    fun displayNameOnly() {
        assertEquals("다니엘", korean(NameStyle.DisplayName))
        assertEquals("Forsen", latin(NameStyle.DisplayName))
    }

    @Test
    fun loginOnly() {
        assertEquals("daniel", korean(NameStyle.Login))
        assertEquals("forsen", latin(NameStyle.Login))
    }

    @Test
    fun bothOnlyForAnotherScript() {
        assertEquals("다니엘 (daniel)", korean(NameStyle.OtherScripts))
        assertEquals("Forsen", latin(NameStyle.OtherScripts))
    }

    /** Written the same, the login would only repeat the name. */
    @Test
    fun alwaysBoth() {
        assertEquals("다니엘 (daniel)", korean(NameStyle.Both))
        assertEquals("Forsen (forsen)", latin(NameStyle.Both))
        assertEquals("xqc", chatterName("xqc", "xqc", NameStyle.Both))
    }

    @Test
    fun aNicknameWinsOverEveryStyle() {
        NameStyle.entries.forEach { assertEquals("Dan", chatterName("다니엘", "daniel", it, nickname = "Dan")) }
    }

    @Test
    fun withoutALoginTheDisplayNameIsAllThereIs() {
        assertEquals("다니엘", chatterName("다니엘", null, NameStyle.Login))
        assertEquals("daniel", chatterName(null, "daniel", NameStyle.Both))
    }
}
