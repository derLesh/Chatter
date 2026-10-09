package dev.chatter.app.emotes

import dev.chatter.app.badges.NamePaint
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
    fun badgeIsDescribedByACosmetic() {
        val event = parse(
            """
            {"type":"cosmetic.create","body":{"id":"C1","kind":"BADGE","object":{"id":"C1","kind":"BADGE",
              "data":{"id":"01GXP5DNHR000CV9HPMT8GM9JZ","name":"Subscriber","tooltip":"7TV Subscriber (1 Year)"}}}}
            """
        )
        assertEquals(SevenTvEvent.BadgeCreated("01GXP5DNHR000CV9HPMT8GM9JZ", "Subscriber", "7TV Subscriber (1 Year)"), event)
    }

    @Test
    fun entitlementNamesTheWearerByTheirTwitchId() {
        val event = parse(
            """
            {"type":"entitlement.create","body":{"id":"E1","object":{"id":"E1","kind":"BADGE","ref_id":"BADGE1",
              "user":{"id":"7TV1","connections":[{"platform":"DISCORD","id":"999"},{"platform":"TWITCH","id":"12345","username":"lukas"}]}}}}
            """
        )
        assertEquals(SevenTvEvent.EntitlementChanged("12345", "BADGE1", worn = true), event)
    }

    @Test
    fun paintIsDescribedByACosmetic() {
        val event = parse(
            """
            {"type":"cosmetic.create","body":{"object":{"id":"P1","kind":"PAINT","data":{
              "id":"P1","name":"Sunset","color":null,"function":"LINEAR_GRADIENT","angle":90,"repeat":false,
              "stops":[{"at":1,"color":-16776961},{"at":0,"color":-65281}],
              "shadows":[{"x_offset":1,"y_offset":2,"radius":0.5,"color":255}]}}}}
            """
        ) as SevenTvEvent.PaintCreated
        val paint = event.paint
        assertEquals("P1", paint.id)
        assertEquals(NamePaint.Kind.Linear, paint.kind)
        assertEquals(90f, paint.angle)
        // RGBA as 7TV sends it: -65281 is 0xFFFF00FF, yellow; -16776961 is 0xFF0000FF, red.
        assertEquals(listOf(0f to 0xFFFFFF00.toInt(), 1f to 0xFFFF0000.toInt()), paint.stops.map { it.at to it.color })
        assertEquals(0xFFFFFF00.toInt(), paint.fallbackColor)
        assertEquals(NamePaint.Shadow(1f, 2f, 0.5f, 0xFF000000.toInt()), paint.shadows.single())
    }

    @Test
    fun imagePaintsOnlyComeFromSevenTv() {
        fun image(url: String) = parse(
            """{"type":"cosmetic.create","body":{"object":{"kind":"PAINT","data":{
              "id":"P2","function":"URL","image_url":"$url","stops":[],"shadows":[]}}}}"""
        )
        val url = "https://cdn.7tv.app/paint/P2/layer/1x.webp"
        assertEquals(url, (image(url) as SevenTvEvent.PaintCreated).paint.imageUrl)
        assertNull(image("https://tracker.example/pixel.webp"))
    }

    @Test
    fun aPaintEntitlementIsToldApartFromABadge() {
        val event = parse(
            """
            {"type":"entitlement.create","body":{"object":{"kind":"PAINT","ref_id":"P1",
              "user":{"connections":[{"platform":"TWITCH","id":"12345"}]}}}}
            """
        )
        assertEquals(SevenTvEvent.EntitlementChanged("12345", "P1", worn = true, SevenTvEvent.Cosmetic.Paint), event)
    }

    @Test
    fun paintsAndUsersWithoutTwitchAreIgnored() {
        // A paint without a known function cannot be drawn.
        assertNull(parse("""{"type":"cosmetic.create","body":{"object":{"kind":"PAINT","data":{"id":"P1"}}}}"""))
        assertNull(
            parse(
                """{"type":"entitlement.create","body":{"object":{"kind":"BADGE","ref_id":"B1",
                  "user":{"connections":[{"platform":"KICK","id":"7"}]}}}}"""
            )
        )
    }

    @Test
    fun unrelatedDispatchIsIgnored() {
        assertNull(parse("""{"type":"user.update","body":{"id":"U","updated":[{"key":"username","value":"x"}]}}"""))
        assertNull(parse("""{"type":"emote_set.update","body":{"id":"S","updated":[{"key":"name","value":"x"}]}}"""))
    }

    private fun heartbeat(json: String) = SevenTvEventClient.heartbeatInterval(AppJson.parseToJsonElement(json).jsonObject)

    @Test
    fun theHelloSaysHowOftenAHeartbeatComes() {
        assertEquals(25_000L, heartbeat("""{"op":1,"d":{"heartbeat_interval":25000,"session_id":"abc","subscription_limit":500}}"""))
    }

    @Test
    fun aMissingOrSillyHeartbeatIntervalFallsBackToSomethingSensible() {
        assertEquals("missing", 30_000L, heartbeat("""{"op":1,"d":{"session_id":"abc"}}"""))
        assertEquals("zero would make the watchdog spin", 5_000L, heartbeat("""{"op":1,"d":{"heartbeat_interval":0}}"""))
        assertEquals("a day would blind it", 300_000L, heartbeat("""{"op":1,"d":{"heartbeat_interval":86400000}}"""))
    }
}
