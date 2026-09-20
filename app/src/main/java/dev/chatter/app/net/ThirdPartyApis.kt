package dev.chatter.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

// ---- BetterTTV ---------------------------------------------------------------

@Serializable
data class BttvEmote(
    val id: String,
    val code: String,
    val animated: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    /** Uploader; only present for shared emotes. */
    val user: BttvUser? = null,
)

@Serializable
data class BttvUser(val displayName: String = "", val name: String = "")

@Serializable
data class BttvChannel(val channelEmotes: List<BttvEmote> = emptyList(), val sharedEmotes: List<BttvEmote> = emptyList())

// ---- FrankerFaceZ ------------------------------------------------------------

@Serializable
data class FfzEmote(
    val id: Long,
    val name: String,
    val width: Int = 28,
    val height: Int = 28,
    val urls: Map<String, String?> = emptyMap(),
    val animated: Map<String, String?>? = null,
    val owner: FfzOwner? = null,
)

@Serializable
data class FfzOwner(@kotlinx.serialization.SerialName("display_name") val displayName: String = "", val name: String = "")

@Serializable
data class FfzSet(val emoticons: List<FfzEmote> = emptyList())

@Serializable
data class FfzGlobal(
    @kotlinx.serialization.SerialName("default_sets") val defaultSets: List<Long> = emptyList(),
    val sets: Map<String, FfzSet> = emptyMap(),
)

@Serializable
data class FfzRoom(val sets: Map<String, FfzSet> = emptyMap())

// ---- 7TV ---------------------------------------------------------------------

@Serializable
data class SevenTvEmoteSet(val id: String = "", val emotes: List<SevenTvActiveEmote>? = null)

@Serializable
data class SevenTvActiveEmote(val id: String, val name: String, val flags: Int = 0, val data: SevenTvEmoteData? = null)

@Serializable
data class SevenTvEmoteData(
    val animated: Boolean = false,
    val flags: Int = 0,
    val listed: Boolean = true,
    val host: SevenTvHost? = null,
    val owner: SevenTvOwner? = null,
)

@Serializable
data class SevenTvOwner(@kotlinx.serialization.SerialName("display_name") val displayName: String = "", val username: String = "")

@Serializable
data class SevenTvHost(val url: String, val files: List<SevenTvFile> = emptyList())

@Serializable
data class SevenTvFile(val name: String, val width: Int = 0, val height: Int = 0, val format: String = "")

@Serializable
data class SevenTvUser(
    @kotlinx.serialization.SerialName("emote_set") val emoteSet: SevenTvEmoteSet? = null,
    /** The 7TV account behind the Twitch connection. */
    val user: SevenTvUserRef? = null,
)

@Serializable
data class SevenTvUserRef(val id: String = "")

// ---- Badges from other clients -----------------------------------------------

/** 7TV hands out badges (subscriber, admin, ...) as "cosmetics", with the wearers listed per badge. */
@Serializable
data class SevenTvCosmetics(val badges: List<SevenTvBadge> = emptyList())

@Serializable
data class SevenTvBadge(
    val id: String = "",
    val name: String = "",
    val tooltip: String = "",
    val host: SevenTvHost? = null,
    /** Twitch user ids of everyone wearing it. */
    val users: List<String> = emptyList(),
)

/** Chatterino's own badge list (contributors, donators, ...), wearers listed by Twitch user id. */
@Serializable
data class ChatterinoBadges(val badges: List<ChatterinoBadge> = emptyList())

@Serializable
data class ChatterinoBadge(
    val tooltip: String = "",
    val image1: String = "",
    val image2: String = "",
    val image3: String = "",
    val users: List<String> = emptyList(),
)

// ---- Recent messages (history) -----------------------------------------------

@Serializable
data class RecentMessages(val messages: List<String> = emptyList(), val error: JsonElement? = null)

class ThirdPartyApi(private val http: OkHttpClient) {
    suspend fun bttvGlobal(): List<BttvEmote> = http.getJson("https://api.betterttv.net/3/cached/emotes/global")
    suspend fun bttvChannel(channelId: String): BttvChannel? =
        http.getJsonOrNull("https://api.betterttv.net/3/cached/users/twitch/$channelId")

    suspend fun ffzGlobal(): FfzGlobal = http.getJson("https://api.frankerfacez.com/v1/set/global")
    suspend fun ffzChannel(channelId: String): FfzRoom? =
        http.getJsonOrNull("https://api.frankerfacez.com/v1/room/id/$channelId")

    suspend fun sevenTvGlobal(): SevenTvEmoteSet = http.getJson("https://7tv.io/v3/emote-sets/global")
    suspend fun sevenTvChannel(channelId: String): SevenTvUser? =
        http.getJsonOrNull("https://7tv.io/v3/users/twitch/$channelId")

    suspend fun sevenTvCosmetics(): SevenTvCosmetics =
        http.getJson("https://7tv.io/v3/cosmetics?user_identifier=twitch_id")

    suspend fun chatterinoBadges(): ChatterinoBadges =
        http.getJson("https://api.chatterino.com/badges")

    suspend fun recentMessages(channel: String, limit: Int): RecentMessages =
        http.getJson("https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=$limit")
}
