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

/** Who supports Chatter, fetched rather than baked in, so a new supporter needs no release. */
@Serializable
data class ChatterSupporters(val supporters: List<ChatterSupporter> = emptyList())

@Serializable
data class ChatterSupporter(
    /** The Twitch user id the badge goes in front of. */
    val twitch: String = "",
    /**
     * How they support: `once` for a one-time sponsorship, `monthly` for a running one. A kind an
     * older version of the app does not know simply wears the plain badge, so another one can be
     * added here without leaving anybody with a broken list.
     */
    val kind: String = KIND_ONCE,
) {
    companion object {
        const val KIND_ONCE = "once"
        const val KIND_MONTHLY = "monthly"
    }
}

// ---- Recent messages (history) -----------------------------------------------

@Serializable
data class RecentMessages(val messages: List<String> = emptyList(), val error: JsonElement? = null)

/**
 * What the emote repository asks the three emote providers for.
 *
 * An interface rather than the class itself, so that everything the repository does with the
 * answers — which provider wins a name, what happens when one does not answer, when it is worth
 * asking again — can be tried out without a network. [ThirdPartyApi] is the one real answer to it.
 */
interface ThirdPartyEmoteApi {
    suspend fun bttvGlobal(): List<BttvEmote>
    suspend fun bttvChannel(channelId: String): BttvChannel?
    suspend fun ffzGlobal(): FfzGlobal
    suspend fun ffzChannel(channelId: String): FfzRoom?
    suspend fun sevenTvGlobal(): SevenTvEmoteSet
    suspend fun sevenTvChannel(channelId: String): SevenTvUser?
}

/** The badge lists other clients hand out, as the badge repository asks for them. */
interface ThirdPartyBadgeApi {
    suspend fun chatterinoBadges(): ChatterinoBadges
    suspend fun chatterSupporters(): ChatterSupporters
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

    /**
     * Served from the project's GitHub Pages (`docs/` on master), not from the repository itself:
     * raw.githubusercontent answers 404 for a private repo, so the list never loaded. It is
     * fetched, never shipped — a new supporter must not need a new release.
     */
    override suspend fun chatterSupporters(): ChatterSupporters =
        http.getJson("https://derlesh.github.io/Chatter/supporters.json")

    suspend fun recentMessages(channel: String, limit: Int): RecentMessages =
        http.getJson("https://recent-messages.robotty.de/api/v2/recent-messages/$channel?limit=$limit")
}
