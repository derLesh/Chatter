package dev.chatter.app.net

import dev.chatter.app.BuildConfig
import dev.chatter.app.emotes.TwitchCheermoteApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Helix answers nobody without a login, so a guest's request is never sent. A separate type so it
 * is not reported as Twitch being unreachable.
 */
class NotLoggedInException : IOException("Twitch's API needs a login")

/** Twitch emotes the user can type, as the emote repository needs them. */
interface TwitchEmoteApi {
    suspend fun userEmotes(userId: String): List<HelixEmote>
    suspend fun channelEmotes(channelId: String): List<HelixEmote>
    suspend fun isFollowing(userId: String, channelId: String): Boolean
}

/** Twitch badges, as the badge repository needs them. */
interface TwitchBadgeApi {
    suspend fun globalBadges(): List<HelixBadgeSet>
    suspend fun channelBadges(channelId: String): List<HelixBadgeSet>
}

/** Client for the parts of the Twitch Helix API the app uses. */
class HelixApi(
    private val http: OkHttpClient,
    /** A currently valid access token, refreshed if needed. */
    private val token: suspend () -> String?,
) : TwitchEmoteApi, TwitchBadgeApi, TwitchCheermoteApi {
    private suspend fun headers() = mapOf(
        "Client-Id" to BuildConfig.TWITCH_CLIENT_ID,
        "Authorization" to "Bearer ${token() ?: throw NotLoggedInException()}",
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

    /** Like [users], for channels only known by id, e.g. Shared Chat partners. */
    suspend fun usersById(ids: List<String>): List<HelixUser> = ids.chunked(100).flatMap { batch ->
        val u = "https://api.twitch.tv/helix/users".toHttpUrl().newBuilder()
            .apply { batch.forEach { addQueryParameter("id", it) } }.build().toString()
        http.getJson<HelixList<HelixUser>>(u, headers()).data
    }

    /** The Shared Chat session of [broadcasterId], or null if it is not sharing its chat. */
    suspend fun sharedChatSession(broadcasterId: String): HelixSharedChatSession? =
        http.getJson<HelixList<HelixSharedChatSession>>(url("shared_chat/session", "broadcaster_id" to broadcasterId), headers())
            .data.firstOrNull()

    suspend fun liveStreams(logins: List<String>): List<HelixStream> = logins.chunked(100).flatMap { batch ->
        val u = "https://api.twitch.tv/helix/streams".toHttpUrl().newBuilder()
            .addQueryParameter("first", "100")
            .apply { batch.forEach { addQueryParameter("user_login", it) } }.build().toString()
        http.getJson<HelixList<HelixStream>>(u, headers()).data
    }

    suspend fun searchChannels(query: String): List<HelixChannelSearch> =
        http.getJson<HelixList<HelixChannelSearch>>(url("search/channels", "query" to query, "first" to "10"), headers()).data

    override suspend fun globalBadges(): List<HelixBadgeSet> =
        http.getJson<HelixList<HelixBadgeSet>>(url("chat/badges/global"), headers()).data

    override suspend fun channelBadges(channelId: String): List<HelixBadgeSet> =
        http.getJson<HelixList<HelixBadgeSet>>(url("chat/badges", "broadcaster_id" to channelId), headers()).data

    override suspend fun cheermotes(channelId: String): List<HelixCheermote> =
        http.getJson<HelixList<HelixCheermote>>(url("bits/cheermotes", "broadcaster_id" to channelId), headers()).data

    /** Every emote the user may use anywhere (subs, follower, globals, ...). Paginated. */
    override suspend fun userEmotes(userId: String): List<HelixEmote> {
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
        http.getJson<HelixList<HelixEmote>>(url("chat/emotes/global"), headers()).data

    /** All Twitch emotes of a channel (sub tiers, bits, follower). */
    override suspend fun channelEmotes(channelId: String): List<HelixEmote> =
        http.getJson<HelixList<HelixEmote>>(url("chat/emotes", "broadcaster_id" to channelId), headers()).data

    /**
     * Who is in a channel's chat. Twitch only answers where the user is moderator or broadcaster;
     * stops after [limit], since big channels have far more chatters than are worth keeping.
     */
    suspend fun chatters(channelId: String, moderatorId: String, limit: Int = 1000): List<HelixChatter> {
        val all = ArrayList<HelixChatter>()
        var cursor: String? = null
        do {
            val page = http.getJson<HelixPagedList<HelixChatter>>(
                url(
                    "chat/chatters", "broadcaster_id" to channelId, "moderator_id" to moderatorId,
                    "first" to "1000", "after" to cursor,
                ),
                headers(),
            )
            all += page.data
            cursor = page.pagination?.cursor
        } while (!cursor.isNullOrEmpty() && all.size < limit)
        return all
    }

    /** The users the logged-in user blocked on Twitch. Paginated. */
    suspend fun blockedUsers(userId: String): List<HelixBlockedUser> {
        val all = ArrayList<HelixBlockedUser>()
        var cursor: String? = null
        do {
            val page = http.getJson<HelixPagedList<HelixBlockedUser>>(
                url("users/blocks", "broadcaster_id" to userId, "first" to "100", "after" to cursor), headers(),
            )
            all += page.data
            cursor = page.pagination?.cursor
        } while (!cursor.isNullOrEmpty())
        return all
    }

    suspend fun setBlocked(targetUserId: String, blocked: Boolean) {
        http.send(
            if (blocked) "PUT" else "DELETE",
            url("users/blocks", "target_user_id" to targetUserId),
            headers(),
        )
    }

    /** How many channels [userId] follows; Twitch sends the total with the first page. */
    suspend fun followedCount(userId: String): Int =
        http.getJson<HelixTotal>(url("channels/followed", "user_id" to userId, "first" to "1"), headers()).total

    override suspend fun isFollowing(userId: String, channelId: String): Boolean =
        http.getJson<HelixList<JsonObject>>(url("channels/followed", "user_id" to userId, "broadcaster_id" to channelId), headers())
            .data.isNotEmpty()

    // ---- Moderation (Twitch removed the IRC slash commands)
    // ----------------------------------------

    /** [durationSeconds] null = permanent ban. */
    suspend fun ban(channelId: String, modId: String, userId: String, durationSeconds: Int?, reason: String?) {
        val body = buildJsonObject {
            putJsonObject("data") {
                put("user_id", userId)
                durationSeconds?.let { put("duration", it) }
                if (!reason.isNullOrBlank()) put("reason", reason)
            }
        }
        http.send("POST", url("moderation/bans", "broadcaster_id" to channelId, "moderator_id" to modId), headers(), body.toString())
    }

    suspend fun unban(channelId: String, modId: String, userId: String) {
        http.send("DELETE", url("moderation/bans", "broadcaster_id" to channelId, "moderator_id" to modId, "user_id" to userId), headers())
    }

    /** Deletes one message, or clears the whole chat when [messageId] is null. */
    suspend fun deleteMessages(channelId: String, modId: String, messageId: String?) {
        http.send("DELETE", url("moderation/chat", "broadcaster_id" to channelId, "moderator_id" to modId, "message_id" to messageId), headers())
    }

    suspend fun updateChatSettings(channelId: String, modId: String, settings: JsonObject) {
        http.send("PATCH", url("chat/settings", "broadcaster_id" to channelId, "moderator_id" to modId), headers(), settings.toString())
    }

    suspend fun setModerator(channelId: String, userId: String, add: Boolean) {
        http.send(if (add) "POST" else "DELETE", url("moderation/moderators", "broadcaster_id" to channelId, "user_id" to userId), headers())
    }

    suspend fun setVip(channelId: String, userId: String, add: Boolean) {
        http.send(if (add) "POST" else "DELETE", url("channels/vips", "broadcaster_id" to channelId, "user_id" to userId), headers())
    }

    suspend fun announce(channelId: String, modId: String, message: String) {
        val body = buildJsonObject { put("message", message) }
        http.send("POST", url("chat/announcements", "broadcaster_id" to channelId, "moderator_id" to modId), headers(), body.toString())
    }

    suspend fun shoutout(channelId: String, modId: String, targetId: String) {
        http.send("POST", url("chat/shoutouts", "from_broadcaster_id" to channelId, "to_broadcaster_id" to targetId, "moderator_id" to modId), headers())
    }

    suspend fun startRaid(channelId: String, targetId: String) {
        http.send("POST", url("raids", "from_broadcaster_id" to channelId, "to_broadcaster_id" to targetId), headers())
    }

    suspend fun cancelRaid(channelId: String) {
        http.send("DELETE", url("raids", "broadcaster_id" to channelId), headers())
    }

    /**
     * Whispers can only be sent through Helix, and only by accounts with a verified phone number; a
     * 403 usually means that.
     */
    suspend fun sendWhisper(fromUserId: String, toUserId: String, message: String) {
        val body = buildJsonObject { put("message", message) }
        http.send("POST", url("whispers", "from_user_id" to fromUserId, "to_user_id" to toUserId), headers(), body.toString())
    }

    /** [color] is a named Twitch color (e.g. "blue_violet") or "#RRGGBB" (Turbo/Prime only). */
    suspend fun setChatColor(userId: String, color: String) {
        http.send("PUT", url("chat/color", "user_id" to userId, "color" to color), headers())
    }
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

/** A list whose length is all that matters; its entries are not parsed. */
@Serializable
data class HelixTotal(val total: Int = 0)

@Serializable
data class Pagination(val cursor: String? = null)

@Serializable
data class HelixUser(
    val id: String,
    val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("profile_image_url") val profileImageUrl: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val description: String = "",
    /** "partner", "affiliate" or "". */
    @SerialName("broadcaster_type") val broadcasterType: String = "",
)

@Serializable
data class HelixSharedChatSession(
    @SerialName("host_broadcaster_id") val hostId: String = "",
    val participants: List<HelixSharedChatParticipant> = emptyList(),
)

@Serializable
data class HelixSharedChatParticipant(@SerialName("broadcaster_id") val broadcasterId: String)

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
data class HelixCheermote(
    val prefix: String = "",
    val tiers: List<HelixCheermoteTier> = emptyList(),
)

@Serializable
data class HelixCheermoteTier(
    @SerialName("min_bits") val minBits: Int = 0,
    /** "#9c3ee8". */
    val color: String = "",
    /** Theme ("dark", "light"), then "animated" or "static", then scale ("1" to "4") to URL. */
    val images: Map<String, Map<String, Map<String, String>>> = emptyMap(),
)

@Serializable
data class HelixChatter(
    @SerialName("user_id") val userId: String,
    @SerialName("user_login") val userLogin: String = "",
    @SerialName("user_name") val userName: String = "",
)

@Serializable
data class HelixBlockedUser(
    @SerialName("user_id") val userId: String,
    @SerialName("user_login") val userLogin: String = "",
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class HelixEmote(
    val id: String,
    val name: String,
    @SerialName("emote_type") val emoteType: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    val format: List<String> = emptyList(),
)
