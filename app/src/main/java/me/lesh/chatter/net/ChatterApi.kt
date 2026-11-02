package me.lesh.chatter.net

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Twitch user ids of the people who support Chatter. */
@Serializable
data class ChatterSupporters(val supporters: List<String> = emptyList())

@Serializable
data class ChatterBadges(val badges: List<ChatterBadge> = emptyList())

@Serializable
data class ChatterBadge(
    val id: String = "",
    val title: String = "",
    /**
     * By the app theme they suit ("dark", "light"), then by scale ("1x", "2x", "4x"); paths relative
     * to the API, like "/v2/chatter/badges/chatter/dark/2x.png".
     */
    val images: Map<String, Map<String, String>> = emptyMap(),
)

/** Chatter's own API, as the badge repository needs it. */
interface ChatterBadgeApi {
    /** Needs a login: the API checks the Twitch token, so a guest's request is never sent. */
    suspend fun supporters(): ChatterSupporters
    suspend fun badges(): ChatterBadges

    /** [path] from [badges] as a URL to load. */
    fun imageUrl(path: String): String?
}

/** Chatter's own API on api.lesh.me: who supports Chatter, and the badge they wear. */
class ChatterApi(
    private val http: OkHttpClient,
    /** A currently valid Twitch access token, refreshed if needed. */
    private val token: suspend () -> String?,
) : ChatterBadgeApi {
    override suspend fun supporters(): ChatterSupporters = http.getJson(
        "$BASE_URL/v2/chatter/supporters",
        mapOf("Authorization" to "Bearer ${token() ?: throw NotLoggedInException()}"),
    )

    override suspend fun badges(): ChatterBadges = http.getJson("$BASE_URL/v2/chatter/badges")

    override fun imageUrl(path: String): String? =
        BASE_URL.toHttpUrl().resolve(path)?.toString()?.let(TrustedImages::url)

    private companion object {
        const val BASE_URL = "https://api.lesh.me"
    }
}
