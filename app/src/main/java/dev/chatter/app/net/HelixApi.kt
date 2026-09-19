package dev.chatter.app.net

import dev.chatter.app.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Minimal client for the parts of the Twitch Helix API the app needs. */
class HelixApi(
    private val http: OkHttpClient,
    /** Returns a currently valid access token (refreshing it if needed). */
    private val token: suspend () -> String?,
) {
    private suspend fun headers() = mapOf(
        "Client-Id" to BuildConfig.TWITCH_CLIENT_ID,
        "Authorization" to "Bearer ${token().orEmpty()}",
    )

    private fun url(path: String, vararg params: Pair<String, String?>): String =
        "https://api.twitch.tv/helix/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build().toString()

    suspend fun validate(accessToken: String): ValidateResponse =
        http.getJson("https://id.twitch.tv/oauth2/validate", mapOf("Authorization" to "OAuth $accessToken"))

    suspend fun users(logins: List<String>): List<HelixUser> = logins.chunked(100).flatMap { batch ->
        val u = "https://api.twitch.tv/helix/users".toHttpUrl().newBuilder()
            .apply { batch.forEach { addQueryParameter("login", it) } }.build().toString()
        http.getJson<HelixList<HelixUser>>(u, headers()).data
    }

    suspend fun liveStreams(logins: List<String>): List<HelixStream> = logins.chunked(100).flatMap { batch ->
        val u = "https://api.twitch.tv/helix/streams".toHttpUrl().newBuilder()
            .addQueryParameter("first", "100")
            .apply { batch.forEach { addQueryParameter("user_login", it) } }.build().toString()
        http.getJson<HelixList<HelixStream>>(u, headers()).data
    }

    suspend fun searchChannels(query: String): List<HelixChannelSearch> =
        http.getJson<HelixList<HelixChannelSearch>>(url("search/channels", "query" to query, "first" to "10"), headers()).data

    suspend fun globalBadges(): List<HelixBadgeSet> =
        http.getJson<HelixList<HelixBadgeSet>>(url("chat/badges/global"), headers()).data

    suspend fun channelBadges(channelId: String): List<HelixBadgeSet> =
        http.getJson<HelixList<HelixBadgeSet>>(url("chat/badges", "broadcaster_id" to channelId), headers()).data

    /** All emotes the user may use anywhere (subs, follower, globals, ...). Paginated. */
    suspend fun userEmotes(userId: String): List<HelixEmote> {
        val all = ArrayList<HelixEmote>()
        var cursor: String? = null
        do {
            val page = http.getJson<HelixPagedList<HelixEmote>>(
                url("chat/emotes/user", "user_id" to userId, "after" to cursor), headers(),
            )
            all += page.data
            cursor = page.pagination?.cursor
        } while (!cursor.isNullOrEmpty())
        return all
    }

    suspend fun globalEmotes(): List<HelixEmote> =

    /** All Twitch emotes of a channel (subscriber tiers, bits, follower). */
    suspend fun channelEmotes(channelId: String): List<HelixEmote> =
        http.getJson<HelixList<HelixEmote>>(url("chat/emotes", "broadcaster_id" to channelId), headers()).data

    suspend fun isFollowing(userId: String, channelId: String): Boolean =
        http.getJson<HelixList<JsonObject>>(url("channels/followed", "user_id" to userId, "broadcaster_id" to channelId), headers())
            .data.isNotEmpty()
        http.getJson<HelixList<HelixEmote>>(url("chat/emotes/global"), headers()).data
}

@Serializable
data class ValidateResponse(
    val login: String,
    @SerialName("user_id") val userId: String,
    val scopes: List<String> = emptyList(),
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
data class HelixList<T>(val data: List<T> = emptyList())

@Serializable
data class HelixPagedList<T>(val data: List<T> = emptyList(), val pagination: Pagination? = null)

@Serializable
data class Pagination(val cursor: String? = null)

@Serializable
data class HelixUser(
    val id: String,
    val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("profile_image_url") val profileImageUrl: String = "",
)

@Serializable
data class HelixStream(
    @SerialName("user_login") val userLogin: String,
    @SerialName("viewer_count") val viewerCount: Int = 0,
    val title: String = "",
    @SerialName("game_name") val gameName: String = "",
)

@Serializable
data class HelixChannelSearch(
    val id: String,
    @SerialName("broadcaster_login") val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
)

@Serializable
data class HelixBadgeSet(
    @SerialName("set_id") val setId: String,
    val versions: List<HelixBadgeVersion> = emptyList(),
)

@Serializable
data class HelixBadgeVersion(
    val id: String,
    val title: String = "",
    @SerialName("image_url_1x") val url1x: String = "",
    @SerialName("image_url_2x") val url2x: String = "",
    @SerialName("image_url_4x") val url4x: String = "",
)

@Serializable
data class HelixEmote(
    val id: String,
    val name: String,
    @SerialName("emote_type") val emoteType: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    val format: List<String> = emptyList(),
)
