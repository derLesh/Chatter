package me.lesh.chatter.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** FFZ's badge list, read the way the app reads it. */
class FfzBadgesTest {
    @Test
    fun theListAsFfzSendsIt() {
        val list = AppJson.decodeFromString<FfzBadges>(
            """{"badges":[{"id":2,"name":"bot","title":"Bot","slot":1,"replaces":"moderator","color":"#595959","image":"https://cdn.frankerfacez.com/badge/2/1","urls":{"1":"https://cdn.frankerfacez.com/badge/2/1","2":"https://cdn.frankerfacez.com/badge/2/2","4":"https://cdn.frankerfacez.com/badge/2/4"},"css":null}],"users":{"2":[73227142,82455692]}}""",
        )
        val bot = list.badges.single()
        assertEquals(2L, bot.id)
        assertEquals("Bot", bot.title)
        assertEquals("#595959", bot.color)
        assertEquals("https://cdn.frankerfacez.com/badge/2/2", bot.urls["2"])
        assertArrayEquals(longArrayOf(73227142, 82455692), list.users["2"])
    }
}
