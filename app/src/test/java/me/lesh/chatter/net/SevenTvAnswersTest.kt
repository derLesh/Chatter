package me.lesh.chatter.net

import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SevenTvAnswersTest {
    @Test
    fun aChannelIsReadWithItsSetAndEditors() {
        val channel = SevenTvAnswers.channel(SevenTvAnswers.gql(CHANNEL))!!
        assertEquals("OWNER", channel.id)
        assertEquals("SET", channel.style?.activeEmoteSetId)
        val editor = channel.editors.single()
        assertEquals("EDITOR", editor.editorId)
        assertEquals("ACCEPTED", editor.state)
        assertEquals(true, editor.permissions.emoteSet.manage)
        assertEquals(SevenTvConnection("TWITCH", "222"), editor.editor?.connections?.single())
    }

    @Test
    fun aChannelWithout7tvIsNull() {
        assertNull(SevenTvAnswers.channel(SevenTvAnswers.gql("""{"data":{"users":{"userByConnection":null}}}""")))
    }

    @Test
    fun aFailedChannelAnswerIsAnErrorNotAMissingAccount() {
        val answer = SevenTvAnswers.gql("""{"data":null,"errors":[{"message":"rate limited","extensions":{"status":429}}]}""")
        val error = assertThrows(HttpException::class.java) { SevenTvAnswers.channel(answer) }
        assertEquals(429, error.code)
    }

    @Test
    fun addingSaysWhatWentWrongByStatus() {
        assertEquals(SevenTvEditResult.Done, SevenTvAnswers.editResult(SevenTvAnswers.gql(ADDED)))
        assertEquals(SevenTvEditResult.LoggedOut, SevenTvAnswers.editResult(SevenTvAnswers.gql(error(401, "LOGIN_REQUIRED you are not logged in"))))
        assertEquals(SevenTvEditResult.NameTaken, SevenTvAnswers.editResult(SevenTvAnswers.gql(error(409, "CONFLICTING_NAME"))))
        assertEquals(
            SevenTvEditResult.Refused("LACKING_PRIVILEGES"),
            SevenTvAnswers.editResult(SevenTvAnswers.gql(error(403, "LACKING_PRIVILEGES"))),
        )
    }

    @Test
    fun ownedSetsAreReadWithTheEmotesFound() {
        val sets = SevenTvAnswers.ownedSets(SevenTvAnswers.gql(OWNED_SETS))!!
        assertEquals(listOf("ACTIVE", "PERSONAL", "HALLOWEEN"), sets.map { it.id })
        assertEquals("Halloween", sets[2].name)
        assertEquals("NORMAL", sets[2].kind)
        assertEquals(SevenTvSetEmote("EMOTE", "OMEGALUL"), sets[0].emotes.items.single())
        assertEquals(emptyList<SevenTvSetEmote>(), sets[1].emotes.items)
    }

    @Test
    fun setNamesAreReadWithoutEmotes() {
        val answer = """{"data":{"users":{"userByConnection":{"ownedEmoteSets":[{"id":"A","name":"Main","kind":"NORMAL"},{"id":"P","name":"Personal","kind":"PERSONAL"}]}}}}"""
        val sets = SevenTvAnswers.ownedSets(SevenTvAnswers.gql(answer))!!
        assertEquals(listOf("A" to "NORMAL", "P" to "PERSONAL"), sets.map { it.id to it.kind })
        assertEquals("Main", sets.first().name)
        assertEquals(emptyList<SevenTvSetEmote>(), sets.first().emotes.items)
    }

    @Test
    fun ownedSetsOfAChannelWithout7tvAreNullAndAFailureThrows() {
        assertNull(SevenTvAnswers.ownedSets(SevenTvAnswers.gql("""{"data":{"users":{"userByConnection":null}}}""")))
        assertThrows(HttpException::class.java) { SevenTvAnswers.ownedSets(SevenTvAnswers.gql(error(500, "INTERNAL"))) }
    }

    @Test
    fun anErrorWithoutExtensionsKeepsItsMessage() {
        val answer = SevenTvAnswers.gql("""{"errors":[{"message":"bad query"}]}""")
        assertEquals(SevenTvEditResult.Refused("bad query"), SevenTvAnswers.editResult(answer))
    }

    @Test
    fun meIsTheUserIdOrNullWhenNotLoggedIn() {
        assertEquals("ME", SevenTvAnswers.me(SevenTvAnswers.gql("""{"data":{"users":{"me":{"id":"ME"}}}}""")))
        assertNull(SevenTvAnswers.me(SevenTvAnswers.gql("""{"data":{"users":{"me":null}}}""")))
        assertNull(SevenTvAnswers.me(SevenTvAnswers.gql("""{"data":null}""")))
    }

    @Test
    fun theLoginStartsAtTwitchWithTheVerifierCookie() {
        val headers = headersOf(
            "Location", AUTHORIZE,
            "Set-Cookie", "seventv-verifier=VERIFIER; HttpOnly; SameSite=Strict; Secure; Path=/; Domain=api.7tv.app; Max-Age=300",
        )
        val start = SevenTvAnswers.loginStart(LOGIN_URL, headers)!!
        assertEquals(AUTHORIZE, start.authorizeUrl)
        assertEquals("VERIFIER", start.verifier)
    }

    @Test
    fun aLoginStartWithoutRedirectOrCookieIsNone() {
        assertNull(SevenTvAnswers.loginStart(LOGIN_URL, headersOf("Location", AUTHORIZE)))
        assertNull(SevenTvAnswers.loginStart(LOGIN_URL, headersOf("Set-Cookie", "seventv-verifier=VERIFIER; Path=/")))
        // A cookie for another site is not 7TV's.
        assertNull(SevenTvAnswers.loginStart(LOGIN_URL, headersOf("Location", AUTHORIZE, "Set-Cookie", "seventv-verifier=X; Domain=evil.example")))
    }

    @Test
    fun theTokenIsReadFromTheFinish() {
        assertEquals("TOKEN", SevenTvAnswers.token("""{"token":"TOKEN"}"""))
        assertNull(SevenTvAnswers.token("""{"status":"Internal Server Error","error_code":12000,"error":"request failed"}"""))
    }

    private fun error(status: Int, message: String) =
        """{"data":null,"errors":[{"message":"$message","extensions":{"code":"X","message":"$message","status":$status}}]}"""

    private companion object {
        val LOGIN_URL = "https://api.7tv.app/v4/auth/login?platform=twitch".toHttpUrl()
        const val AUTHORIZE = "https://id.twitch.tv/oauth2/authorize?client_id=abc&redirect_uri=https%3A%2F%2F7tv.app%2Flogin%2Fcallback%3Fplatform%3Dtwitch"

        val CHANNEL = """
            {"data":{"users":{"userByConnection":{"id":"OWNER","style":{"activeEmoteSetId":"SET"},"editors":[
              {"editorId":"EDITOR","state":"ACCEPTED","permissions":{"emoteSet":{"manage":true}},
               "editor":{"connections":[{"platform":"TWITCH","platformId":"222"}]}}]}}}}
        """.trimIndent()

        const val ADDED = """{"data":{"emoteSets":{"emoteSet":{"addEmote":{"id":"SET"}}}}}"""

        /** Shortened from 7TV's answer to the owned sets query in SevenTvApi. */
        val OWNED_SETS = """
            {"data":{"users":{"userByConnection":{"ownedEmoteSets":[
              {"id":"ACTIVE","name":"Emotes","kind":"NORMAL","emotes":{"items":[{"id":"EMOTE","alias":"OMEGALUL"}]}},
              {"id":"PERSONAL","name":"Personal Emotes","kind":"PERSONAL","emotes":{"items":[]}},
              {"id":"HALLOWEEN","name":"Halloween","kind":"NORMAL","emotes":{"items":[{"id":"OTHER","alias":"OMEGALULiguess"}]}}]}}}}
        """.trimIndent()
    }
}
