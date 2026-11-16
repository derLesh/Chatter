package me.lesh.chatter.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class SevenTvEmoteInfo(
    val id: String = "",
    val name: String = "",
    val owner: SevenTvOwner? = null,
    val host: SevenTvHost? = null,
)

/** A channel's 7TV account as far as adding emotes needs it: its active emote set and its editors. */
@Serializable
data class SevenTvChannel(
    val id: String = "",
    val style: SevenTvStyle? = null,
    val editors: List<SevenTvChannelEditor> = emptyList(),
)

@Serializable
data class SevenTvStyle(val activeEmoteSetId: String? = null)

/** [state] is "ACCEPTED" once the editor took the invitation, else "PENDING" or "REJECTED". */
@Serializable
data class SevenTvChannelEditor(
    val editorId: String = "",
    val state: String = "",
    val permissions: SevenTvEditorPermissions = SevenTvEditorPermissions(),
    val editor: SevenTvConnections? = null,
)

@Serializable
data class SevenTvEditorPermissions(val emoteSet: SevenTvPermission = SevenTvPermission())

@Serializable
data class SevenTvPermission(val manage: Boolean = false)

@Serializable
data class SevenTvConnections(val connections: List<SevenTvConnection> = emptyList())

/** [platform] is "TWITCH", "KICK", "DISCORD" or "GOOGLE". */
@Serializable
data class SevenTvConnection(val platform: String = "", val platformId: String = "")

@Serializable
private data class ChannelData(val users: ChannelUsers? = null)

@Serializable
private data class ChannelUsers(val userByConnection: SevenTvChannel? = null)

/** The first step of 7TV's login: the Twitch page to open, and the cookie [SevenTvApi.finishLogin] must send back. */
class SevenTvLoginStart(val authorizeUrl: String, val verifier: String) {
    override fun toString() = "SevenTvLoginStart(‹redacted›)"
}

/** How adding an emote to a set went. */
sealed interface SevenTvAddResult {
    data object Added : SevenTvAddResult

    /** The set has an emote of that name already. */
    data object NameTaken : SevenTvAddResult

    /** The login is no longer valid. */
    data object LoggedOut : SevenTvAddResult

    /** 7TV said no; [message] is its own words, in English. */
    data class Refused(val message: String) : SevenTvAddResult
}

/**
 * 7TV's API for what needs more than the emote lists in [ThirdPartyApi]: an emote of its own, who
 * may edit a channel's emotes, the login, and adding an emote to a set.
 */
class SevenTvApi(private val http: OkHttpClient) {
    /** OkHttp would follow the login's redirect to Twitch; the app opens that in the WebView instead. */
    private val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Null if 7TV has no such emote. */
    suspend fun emote(id: String): SevenTvEmoteInfo? = http.getJsonOrNull("$V3/emotes/$id")

    /**
     * The 7TV account of the Twitch channel [channelId], with its editors' Twitch accounts; null if
     * it has none. Public, so it is asked without a login and says nothing about the user.
     */
    suspend fun channel(channelId: String): SevenTvChannel? =
        SevenTvAnswers.channel(gql(null, CHANNEL, buildJsonObject { put("id", channelId) }))

    /**
     * Starts 7TV's own login. Without a Referer, 7TV sends Twitch's answer to its website's
     * callback, which the login WebView catches; see [me.lesh.chatter.auth.SevenTvLogin].
     */
    suspend fun startLogin(): SevenTvLoginStart = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$V4/auth/login?platform=twitch").build()
        noRedirects.newCall(request).execute().use { response ->
            SevenTvAnswers.loginStart(request.url, response.headers) ?: throw HttpException(response.code, "No login redirect")
        }
    }

    /** The session token for the [code] Twitch passed to 7TV's callback. */
    suspend fun finishLogin(start: SevenTvLoginStart, code: String): String {
        val body = buildJsonObject {
            put("platform", "twitch")
            put("code", code)
        }
        val answer = http.send("POST", "$V4/auth/login/finish", mapOf("Cookie" to "${SevenTvAnswers.VERIFIER_COOKIE}=${start.verifier}"), body.toString())
        return SevenTvAnswers.token(answer) ?: throw HttpException(200, "No token")
    }

    /** Ends the session at 7TV, so the token stops working there too. */
    suspend fun logout(token: String) {
        http.send("POST", "$V4/auth/logout", mapOf("Authorization" to "Bearer $token"))
    }

    /** The 7TV user id [token] belongs to, or null if 7TV no longer accepts it. */
    suspend fun me(token: String): String? = SevenTvAnswers.me(gql(token, "query Me { users { me { id } } }", JsonObject(emptyMap())))

    /** Adds the emote [emoteId] to the set [setId] under [alias]. */
    suspend fun addEmote(token: String, setId: String, emoteId: String, alias: String): SevenTvAddResult {
        val variables = buildJsonObject {
            put("setId", setId)
            putJsonObject("emote") {
                put("emoteId", emoteId)
                put("alias", alias)
            }
        }
        return SevenTvAnswers.addResult(gql(token, ADD_EMOTE, variables))
    }

    private suspend fun gql(token: String?, query: String, variables: JsonObject): GqlAnswer {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val text = try {
            val headers = if (token == null) emptyMap() else mapOf("Authorization" to "Bearer $token")
            http.send("POST", "$V4/gql", headers, body.toString())
        } catch (e: HttpException) {
            // 7TV answers a refused login with an error status as well as with a GraphQL error.
            if (e.code == 401) return GqlAnswer(null, listOf(GqlError("Login required", 401)))
            throw e
        }
        return SevenTvAnswers.gql(text)
    }

    private companion object {
        const val V3 = "https://7tv.io/v3"
        const val V4 = "https://api.7tv.app/v4"

        val CHANNEL = """
            query Channel(${'$'}id: String!) {
              users {
                userByConnection(platform: TWITCH, platformId: ${'$'}id) {
                  id
                  style { activeEmoteSetId }
                  editors { editorId state permissions { emoteSet { manage } } editor { connections { platform platformId } } }
                }
              }
            }
        """.trimIndent()

        val ADD_EMOTE = """
            mutation AddEmote(${'$'}setId: Id!, ${'$'}emote: EmoteSetEmoteId!) {
              emoteSets { emoteSet(id: ${'$'}setId) { addEmote(id: ${'$'}emote) { id } } }
            }
        """.trimIndent()
    }
}

internal class GqlAnswer(val data: JsonElement?, val errors: List<GqlError>)

/** [status] is the HTTP status 7TV puts into the error, like 401 or 409. */
internal class GqlError(val message: String, val status: Int?)

/** Reads what 7TV answers, kept apart from the requests for the tests. */
internal object SevenTvAnswers {
    const val VERIFIER_COOKIE = "seventv-verifier"

    /** A GraphQL answer; 7TV's own words for an error are in its extensions. */
    fun gql(text: String): GqlAnswer {
        val root = AppJson.parseToJsonElement(text).jsonObject
        val errors = (root["errors"] as? JsonArray)?.map { element ->
            val error = element.jsonObject
            val extensions = error["extensions"] as? JsonObject
            GqlError(
                message = (extensions?.get("message") ?: error["message"])?.jsonPrimitive?.contentOrNull.orEmpty(),
                status = extensions?.get("status")?.jsonPrimitive?.intOrNull,
            )
        }.orEmpty()
        return GqlAnswer(root["data"], errors)
    }

    /** Throws on an error, so a failed answer is not taken for a channel without a 7TV account. */
    fun channel(answer: GqlAnswer): SevenTvChannel? {
        answer.errors.firstOrNull()?.let { throw HttpException(it.status ?: 200, it.message) }
        val data = answer.data ?: return null
        return AppJson.decodeFromJsonElement(ChannelData.serializer(), data).users?.userByConnection
    }

    fun me(answer: GqlAnswer): String? =
        ((answer.data as? JsonObject)?.get("users") as? JsonObject)?.get("me")?.let { me ->
            (me as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }

    fun addResult(answer: GqlAnswer): SevenTvAddResult {
        val error = answer.errors.firstOrNull() ?: return SevenTvAddResult.Added
        return when (error.status) {
            401 -> SevenTvAddResult.LoggedOut
            409 -> SevenTvAddResult.NameTaken
            else -> SevenTvAddResult.Refused(error.message)
        }
    }

    /** The redirect to Twitch and the verifier cookie from the answer to [url], or null without both. */
    fun loginStart(url: HttpUrl, headers: Headers): SevenTvLoginStart? {
        val location = headers["Location"] ?: return null
        val verifier = Cookie.parseAll(url, headers).firstOrNull { it.name == VERIFIER_COOKIE }?.value ?: return null
        return SevenTvLoginStart(location, verifier)
    }

    fun token(text: String): String? = (AppJson.parseToJsonElement(text) as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull
}
