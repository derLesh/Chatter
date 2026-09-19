package dev.chatter.app.emotes

import dev.chatter.app.net.AppJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SevenTvEventTest {
    private fun parse(json: String) = SevenTvEventClient.parseDispatch(AppJson.parseToJsonElement(json).jsonObject)

    @Test
    fun emoteSetUpdate() {
        val event = parse(
            """
            {"type":"emote_set.update","body":{"id":"SET1","actor":{"display_name":"Lesh"},
              "pushed":[{"key":"emotes","index":0,"value":{"id":"E1","name":"catJAM","data":{"host":{"url":"//cdn.7tv.app/emote/E1","files":[]}}}}],
              "pulled":[{"key":"emotes","index":1,"old_value":{"id":"E2","name":"OldOne"}}],
              "updated":[{"key":"emotes","index":2,"old_value":{"id":"E3","name":"Before"},"value":{"id":"E3","name":"After"}}]}}
            """
        ) as SevenTvEvent.EmoteSetUpdate
        assertEquals("SET1", event.setId)
        assertEquals("Lesh", event.actor)
        assertEquals(listOf("catJAM"), event.added.map { it.name })
        assertEquals(listOf("OldOne"), event.removed.map { it.name })
        assertEquals(listOf("Before" to "After"), event.renamed.map { it.first.name to it.second.name })
    }

    @Test
    fun activeSetSwitch() {
        val event = parse(
            """
            {"type":"user.update","body":{"id":"USER1","actor":{"display_name":"Mod"},
              "updated":[{"key":"connections","index":0,"value":[
                {"key":"emote_set","old_value":{"id":"OLD"},"value":{"id":"NEW"}}]}]}}
            """
        )
        assertEquals(SevenTvEvent.ActiveSetChanged("USER1", "Mod", "NEW"), event)
    }

    @Test
    fun unrelatedDispatchIsIgnored() {
        assertNull(parse("""{"type":"user.update","body":{"id":"U","updated":[{"key":"username","value":"x"}]}}"""))
        assertNull(parse("""{"type":"emote_set.update","body":{"id":"S","updated":[{"key":"name","value":"x"}]}}"""))
    }
}
