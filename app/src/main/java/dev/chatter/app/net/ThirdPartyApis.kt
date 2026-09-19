package dev.chatter.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

// ---- BetterTTV ---------------------------------------------------------------

@Serializable
data class BttvEmote(val id: String, val code: String, val animated: Boolean = false, val width: Int? = null, val height: Int? = null)

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
)

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
data class SevenTvEmoteSet(val emotes: List<SevenTvActiveEmote>? = null)

@Serializable
data class SevenTvActiveEmote(val id: String, val name: String, val flags: Int = 0, val data: SevenTvEmoteData? = null)

@Serializable
data class SevenTvEmoteData(val animated: Boolean = false, val flags: Int = 0, val host: SevenTvHost? = null)

@Serializable
data class SevenTvHost(val url: String, val files: List<SevenTvFile> = emptyList())

@Serializable
data class SevenTvFile(val name: String, val width: Int = 0, val height: Int = 0, val format: String = "")

@Serializable
data class SevenTvUser(@kotlinx.serialization.SerialName("emote_set") val emoteSet: SevenTvEmoteSet? = null)

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

    suspend fun recentMessages(channel: String, limit: Int): RecentMessages =
        http.getJson("https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=$limit")
}
