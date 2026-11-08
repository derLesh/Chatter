package me.lesh.chatter.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Chatter's API answers, read the way the app reads it. */
class ChatterApiTest {
    private val api = ChatterApi(OkHttpClient())

    private inline fun <reified T> read(json: String): T = AppJson.decodeFromJsonElement(AppJson.parseToJsonElement(json))

    @Test
    fun theBadgeListAsTheApiSendsIt() {
        val list = read<ChatterBadges>(
            """{"badges":[{"id":"chatter","title":"Chatter","images":{"dark":{"1x":"/v2/chatter/badges/chatter/dark/1x.png","2x":"/v2/chatter/badges/chatter/dark/2x.png","4x":"/v2/chatter/badges/chatter/dark/4x.png"},"light":{"1x":"/v2/chatter/badges/chatter/light/1x.png","2x":"/v2/chatter/badges/chatter/light/2x.png","4x":"/v2/chatter/badges/chatter/light/4x.png"}}}]}""",
        )
        val badge = list.badges.single()
        assertEquals("chatter", badge.id)
        assertEquals("/v2/chatter/badges/chatter/dark/2x.png", badge.images["dark"]?.get("2x"))
        assertEquals("/v2/chatter/badges/chatter/light/2x.png", badge.images["light"]?.get("2x"))
    }

    @Test
    fun theSupporterListAsTheApiSendsIt() {
        assertEquals(listOf("12345678", "87654321"), read<ChatterSupporters>("""{"supporters":["12345678","87654321"]}""").supporters)
    }

    @Test
    fun theSupporterListIsAskedForWithoutTheLogin() = runTest {
        val sent = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            sent += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"supporters":["7"]}""".toResponseBody())
                .build()
        }.build()

        assertEquals(listOf("7"), ChatterApi(http).supporters().supporters)
        assertEquals("https://api.lesh.me/v2/chatter/supporters", sent.single().url.toString())
        assertNull(sent.single().header("Authorization"))
    }

    @Test
    fun aPictureIsLoadedFromTheApi() {
        assertEquals(
            "https://api.lesh.me/v2/chatter/badges/chatter/light/2x.png",
            api.imageUrl("/v2/chatter/badges/chatter/light/2x.png"),
        )
    }

    @Test
    fun aPictureElsewhereIsNotLoaded() {
        // Every chatter would fetch it, handing that host their IP address.
        assertNull(api.imageUrl("https://example.com/badge.png"))
        assertNull(api.imageUrl("//example.com/badge.png"))
        assertNull(api.imageUrl("http://api.lesh.me/v2/chatter/badges/chatter/2x.png"))
    }
}
