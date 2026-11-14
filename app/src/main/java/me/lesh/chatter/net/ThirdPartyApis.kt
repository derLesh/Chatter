package me.lesh.chatter.net

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
    /** Uploader; only set for shared emotes. */
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
    /** An effect emote; see [me.lesh.chatter.emotes.FfzEffects]. */
    val modifier: Boolean = false,
    @kotlinx.serialization.SerialName("modifier_flags") val modifierFlags: Int = 0,
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
data class FfzRoom(val sets: Map<String, FfzSet> = emptyMap(), val room: FfzRoomInfo? = null)

/** The mod and VIP pictures a channel picked on FFZ, by scale ("1", "2", "4"); null without one. */
@Serializable
data class FfzRoomInfo(
    @kotlinx.serialization.SerialName("mod_urls") val modUrls: Map<String, String?>? = null,
    @kotlinx.serialization.SerialName("vip_badge") val vipBadge: Map<String, String?>? = null,
)

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

/**
 * FFZ's badges and who wears them: [users] maps a badge id to Twitch user ids. Tens of thousands
 * of ids, so read straight into arrays rather than boxed numbers.
 */
@Serializable
class FfzBadges(val badges: List<FfzBadge> = emptyList(), val users: Map<String, LongArray> = emptyMap())

@Serializable
data class FfzBadge(
    val id: Long,
    val name: String = "",
    val title: String = "",
    /** Drawn behind the picture, which is a white symbol; "#RRGGBB". */
    val color: String? = null,
    /** Picture by scale: "1", "2", "4". */
    val urls: Map<String, String?> = emptyMap(),
)

// ---- Recent messages (history) -----------------------------------------------

@Serializable
data class RecentMessages(val messages: List<String> = emptyList(), val error: JsonElement? = null)

/**
 * What the emote repository needs from the three emote providers. An interface so the repository's
 * rules can be tested without a network.
 */
interface ThirdPartyEmoteApi {
    suspend fun bttvGlobal(): List<BttvEmote>
    suspend fun bttvChannel(channelId: String): BttvChannel?
    suspend fun ffzGlobal(): FfzGlobal
    suspend fun ffzChannel(channelId: String): FfzRoom?
    suspend fun sevenTvGlobal(): SevenTvEmoteSet
    suspend fun sevenTvChannel(channelId: String): SevenTvUser?
}

/** The badge lists of other clients, as the badge repository needs them. */
interface ThirdPartyBadgeApi {
    suspend fun chatterinoBadges(): ChatterinoBadges
    suspend fun ffzBadges(): FfzBadges
    suspend fun ffzRoom(channelId: String): FfzRoom?
}

class ThirdPartyApi(private val http: OkHttpClient) : ThirdPartyEmoteApi, ThirdPartyBadgeApi {
    override suspend fun bttvGlobal(): List<BttvEmote> = http.getJson("https://api.betterttv.net/3/cached/emotes/global")
    override suspend fun bttvChannel(channelId: String): BttvChannel? =
        http.getJsonOrNull("https://api.betterttv.net/3/cached/users/twitch/$channelId")

    override suspend fun ffzGlobal(): FfzGlobal = http.getJson("https://api.frankerfacez.com/v1/set/global")
    override suspend fun ffzChannel(channelId: String): FfzRoom? =
        http.getJsonOrNull("https://api.frankerfacez.com/v1/room/id/$channelId")

    override suspend fun sevenTvGlobal(): SevenTvEmoteSet = http.getJson("https://7tv.io/v3/emote-sets/global")
    override suspend fun sevenTvChannel(channelId: String): SevenTvUser? =
        http.getJsonOrNull("https://7tv.io/v3/users/twitch/$channelId")

    override suspend fun chatterinoBadges(): ChatterinoBadges =
        http.getJson("https://api.chatterino.com/badges")

    override suspend fun ffzBadges(): FfzBadges = http.getJson("https://api.frankerfacez.com/v1/badges/ids")
    override suspend fun ffzRoom(channelId: String): FfzRoom? = ffzChannel(channelId)

    suspend fun recentMessages(channel: String, limit: Int): RecentMessages =
        http.getJson("https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=$limit")
}
