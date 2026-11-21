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

/**
 * An emote set a channel owns. [kind] is "NORMAL" for the sets a channel switches between, else
 * "PERSONAL", "GLOBAL" or "SPECIAL". [emotes] holds only those found by the name asked for.
 */
@Serializable
data class SevenTvOwnedSet(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val emotes: SevenTvSetEmotes = SevenTvSetEmotes(),
)

@Serializable
data class SevenTvSetEmotes(val items: List<SevenTvSetEmote> = emptyList())

/** [id] is the emote's, [alias] the name it has in the set. */
@Serializable
data class SevenTvSetEmote(val id: String = "", val alias: String = "")

@Serializable
private data class SetsData(val users: SetsUsers? = null)

@Serializable
private data class SetsUsers(val userByConnection: SetsOwner? = null)

@Serializable
private data class SetsOwner(val ownedEmoteSets: List<SevenTvOwnedSet> = emptyList())

/** The first step of 7TV's login: the Twitch page to open, and the cookie [SevenTvApi.finishLogin] must send back. */
class SevenTvLoginStart(val authorizeUrl: String, val verifier: String) {
    override fun toString() = "SevenTvLoginStart(‹redacted›)"
}

/** How adding, renaming or removing an emote in a set, or switching sets, went. */
sealed interface SevenTvEditResult {
    data object Done : SevenTvEditResult

    /** The set has an emote of that name already. */
    data object NameTaken : SevenTvEditResult

    /** The login is no longer valid. */
    data object LoggedOut : SevenTvEditResult

    /** 7TV said no; [message] is its own words, in English. */
    data class Refused(val message: String) : SevenTvEditResult
}

/**
 * 7TV's API for what needs more than the emote lists in [ThirdPartyApi]: an emote of its own, who
 * may edit a channel's emotes, the login, and changing the emotes of a set.
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
    suspend fun addEmote(token: String, setId: String, emoteId: String, alias: String): SevenTvEditResult =
        SevenTvAnswers.editResult(gql(token, ADD_EMOTE, setEmote(setId, emoteId, alias)))

    /**
     * The emote sets the 7TV account of the Twitch channel [channelId] owns, each with its emotes
     * whose name contains [search]. Public like [channel]; null if the channel has no 7TV account.
     */
    suspend fun ownedSets(channelId: String, search: String): List<SevenTvOwnedSet>? {
        val variables = buildJsonObject {
            put("id", channelId)
            put("search", search)
        }
        return SevenTvAnswers.ownedSets(gql(null, OWNED_SETS, variables))
    }

    /**
     * The emote sets the 7TV account of the Twitch channel [channelId] owns, without their emotes.
     * Public like [channel]; null if the channel has no 7TV account.
     */
    suspend fun setNames(channelId: String): List<SevenTvOwnedSet>? =
        SevenTvAnswers.ownedSets(gql(null, SET_NAMES, buildJsonObject { put("id", channelId) }))

    /** Makes [setId] the active emote set of the 7TV user [userId]. */
    suspend fun activateSet(token: String, userId: String, setId: String): SevenTvEditResult {
        val variables = buildJsonObject {
            put("userId", userId)
            put("setId", setId)
        }
        return SevenTvAnswers.editResult(gql(token, ACTIVATE_SET, variables))
    }

    /** Takes the emote [emoteId], named [alias] there, out of the set [setId]. */
    suspend fun removeEmote(token: String, setId: String, emoteId: String, alias: String): SevenTvEditResult =
        SevenTvAnswers.editResult(gql(token, REMOVE_EMOTE, setEmote(setId, emoteId, alias)))

    /** Renames the emote [emoteId] in the set [setId] from [alias] to [newAlias]. */
    suspend fun renameEmote(token: String, setId: String, emoteId: String, alias: String, newAlias: String): SevenTvEditResult {
        val variables = buildJsonObject {
            put("setId", setId)
            putJsonObject("emote") {
                put("emoteId", emoteId)
                put("alias", alias)
            }
            put("alias", newAlias)
        }
        return SevenTvAnswers.editResult(gql(token, RENAME_EMOTE, variables))
    }

    /** A set can hold the same emote under several names, so 7TV names one by both. */
    private fun setEmote(setId: String, emoteId: String, alias: String) = buildJsonObject {
        put("setId", setId)
        putJsonObject("emote") {
            put("emoteId", emoteId)
            put("alias", alias)
        }
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

        /**
         * 7TV's search finds the text anywhere in the name an emote has in the set, so the caller
         * matches by id; a short name like L matches most of a set, hence a page as large as a set.
         */
        val OWNED_SETS = """
            query OwnedSets(${'$'}id: String!, ${'$'}search: String!) {
              users {
                userByConnection(platform: TWITCH, platformId: ${'$'}id) {
                  ownedEmoteSets { id name kind emotes(query: ${'$'}search, page: 1, perPage: 1000) { items { id alias } } }
                }
              }
            }
        """.trimIndent()

        val SET_NAMES = """
            query SetNames(${'$'}id: String!) {
              users { userByConnection(platform: TWITCH, platformId: ${'$'}id) { ownedEmoteSets { id name kind } } }
            }
        """.trimIndent()

        val ACTIVATE_SET = """
            mutation ActivateSet(${'$'}userId: Id!, ${'$'}setId: Id!) {
              users { user(id: ${'$'}userId) { activeEmoteSet(emoteSetId: ${'$'}setId) { id } } }
            }
        """.trimIndent()

        val REMOVE_EMOTE = """
            mutation RemoveEmote(${'$'}setId: Id!, ${'$'}emote: EmoteSetEmoteId!) {
              emoteSets { emoteSet(id: ${'$'}setId) { removeEmote(id: ${'$'}emote) { id } } }
            }
        """.trimIndent()

        val RENAME_EMOTE = """
            mutation RenameEmote(${'$'}setId: Id!, ${'$'}emote: EmoteSetEmoteId!, ${'$'}alias: String!) {
              emoteSets { emoteSet(id: ${'$'}setId) { updateEmoteAlias(id: ${'$'}emote, alias: ${'$'}alias) { id } } }
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

    /** Throws on an error, like [channel]. */
    fun ownedSets(answer: GqlAnswer): List<SevenTvOwnedSet>? {
        answer.errors.firstOrNull()?.let { throw HttpException(it.status ?: 200, it.message) }
        val data = answer.data ?: return null
        return AppJson.decodeFromJsonElement(SetsData.serializer(), data).users?.userByConnection?.ownedEmoteSets
    }

    fun me(answer: GqlAnswer): String? =
        ((answer.data as? JsonObject)?.get("users") as? JsonObject)?.get("me")?.let { me ->
            (me as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }

    fun editResult(answer: GqlAnswer): SevenTvEditResult {
        val error = answer.errors.firstOrNull() ?: return SevenTvEditResult.Done
        return when (error.status) {
            401 -> SevenTvEditResult.LoggedOut
            409 -> SevenTvEditResult.NameTaken
            else -> SevenTvEditResult.Refused(error.message)
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
