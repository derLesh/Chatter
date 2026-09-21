package dev.chatter.app.badges

import android.util.Log
import dev.chatter.app.chat.BadgeSource
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixBadgeSet
import dev.chatter.app.net.ThirdPartyApi
import java.util.concurrent.ConcurrentHashMap

/** Where a badge comes from. Each one can be turned off on its own in the settings. */
enum class BadgeProvider { Twitch, SevenTv, Chatterino, Chatter }

data class Badge(val url: String, val title: String, val provider: BadgeProvider = BadgeProvider.Twitch)

/**
 * Resolves the `badges` IRC tag (e.g. "moderator/1,subscriber/12") to images, and adds the
 * badges other clients hand out, which are tied to the Twitch user id rather than to a tag.
 */
class BadgeRepository(
    private val helix: HelixApi,
    private val thirdParty: ThirdPartyApi,
) : BadgeSource {
    @Volatile private var global: Map<String, Badge> = emptyMap()
    private val channels = ConcurrentHashMap<String, Map<String, Badge>>()

    /** Third-party badges by Twitch user id; a user can wear more than one. */
    @Volatile private var thirdPartyBadges: Map<String, List<Badge>> = emptyMap()

    /**
     * The two fetched lists behind [thirdPartyBadges], each null until it has been fetched once.
     *
     * Kept apart so a provider that was unreachable can be fetched on its own later, without the
     * one that did answer being thrown away and asked for again.
     */
    @Volatile private var chatterinoBadges: Map<String, List<Badge>>? = null
    @Volatile private var supporterBadges: Map<String, List<Badge>>? = null

    /**
     * 7TV badges, which are not a list one can fetch: since the cosmetics endpoint was retired
     * they only arrive over the EventAPI, as a description of the badge ([sevenTvBadge]) and,
     * separately, the people wearing it ([sevenTvWearer]). Chatterino does the same.
     */
    private val sevenTvCosmetics = ConcurrentHashMap<String, Badge>()

    /** Twitch user id to the cosmetic id they wear; 7TV shows one badge per person. */
    private val sevenTvWearers = ConcurrentHashMap<String, String>()

    /** Channels whose badges did not load, to be tried again when the app comes back. */
    private val failedChannels = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var lastRetry = 0L

    /** Which providers' badges are shown. Set from the settings, read on every message. */
    @Volatile var enabled: Set<BadgeProvider> = BadgeProvider.entries.toSet()

    override fun resolve(channelId: String?, badgesTag: String?, userId: String?): List<Badge> {
        val providers = enabled
        val twitch = if (badgesTag.isNullOrEmpty() || BadgeProvider.Twitch !in providers) emptyList() else {
            val channel = channelId?.let { channels[it] }
            badgesTag.split(',').mapNotNull { key -> channel?.get(key) ?: global[key] }
        }
        val extra = userId?.let { thirdPartyBadges[it] }?.filter { it.provider in providers }.orEmpty()
        val sevenTv = if (BadgeProvider.SevenTv in providers) userId?.let(::sevenTvBadgeOf) else null
        return when {
            extra.isEmpty() && sevenTv == null -> twitch
            sevenTv == null -> twitch + extra
            else -> twitch + extra + sevenTv
        }
    }

    private fun sevenTvBadgeOf(userId: String): Badge? =
        sevenTvWearers[userId]?.let { sevenTvCosmetics[it] }

    /** A badge 7TV described over the EventAPI. */
    fun sevenTvBadge(id: String, name: String, tooltip: String) {
        sevenTvCosmetics[id] = Badge(
            url = "https://cdn.7tv.app/badge/$id/2x.webp",
            title = tooltip.ifEmpty { name },
            provider = BadgeProvider.SevenTv,
        )
    }

    /** Somebody started or stopped wearing one, by Twitch user id. */
    fun sevenTvWearer(userId: String, cosmeticId: String, worn: Boolean) {
        if (worn) sevenTvWearers[userId] = cosmeticId
        else sevenTvWearers.remove(userId, cosmeticId)
    }

    suspend fun loadGlobal() {
        if (global.isNotEmpty()) return
        runCatching { helix.globalBadges() }
            .onSuccess { global = it.toMap() }
            .onFailure { Log.w(TAG, "Global badges failed: ${it.message}") }
    }

    suspend fun loadChannel(channelId: String) {
        runCatching { helix.channelBadges(channelId) }
            .onSuccess {
                channels[channelId] = it.toMap()
                failedChannels.remove(channelId)
            }
            .onFailure {
                failedChannels.add(channelId)
                Log.w(TAG, "Channel badges failed: ${it.message}")
            }
    }

    /**
     * Fetches whatever did not load earlier: the global set, the other clients' lists, and the
     * channels that were unreachable. Everything that is already there is left alone, so this
     * costs nothing on the usual return to the app.
     */
    suspend fun retryMissing(supporterTitle: String) {
        // A provider that is down stays down for a while, and the app is opened often; asking on
        // every single return would be the kind of traffic a phone in a pocket should not make.
        val now = System.currentTimeMillis()
        if (now - lastRetry < RETRY_AFTER_MS) return
        lastRetry = now
        loadGlobal()
        loadThirdParty(supporterTitle)
        failedChannels.toList().forEach { loadChannel(it) }
    }

    /**
     * Chatterino's badge list and Chatter's own supporters. Each is one request for everybody, so
     * each is fetched once and then only looked up by user id — and only the ones still missing
     * are asked for, so a list that was unreachable at start is picked up later instead of being
     * gone for good. 7TV is not among them; its badges arrive over the EventAPI.
     */
    suspend fun loadThirdParty(supporterTitle: String) {
        var changed = false

        if (chatterinoBadges == null) {
            runCatching { thirdParty.chatterinoBadges() }
                .onSuccess { list ->
                    val byUser = HashMap<String, MutableList<Badge>>()
                    for (badge in list.badges) {
                        val url = badge.image2.ifEmpty { badge.image1 }.ifEmpty { badge.image3 }
                        if (url.isEmpty()) continue
                        val image = Badge(url, badge.tooltip, BadgeProvider.Chatterino)
                        badge.users.forEach { byUser.getOrPut(it) { ArrayList(1) } += image }
                    }
                    chatterinoBadges = byUser
                    changed = true
                }
                .onFailure { Log.w(TAG, "Chatterino badges failed: ${it.message}") }
        }

        if (supporterBadges == null) {
            runCatching { thirdParty.chatterSupporters() }
                .onSuccess { supporters ->
                    val badge = Badge(SUPPORTER_BADGE_URL, supporterTitle, BadgeProvider.Chatter)
                    supporterBadges = supporters.users.associateWith { listOf(badge) }
                    changed = true
                }
                .onFailure { Log.w(TAG, "Supporter list failed: ${it.message}") }
        }

        if (changed) thirdPartyBadges = mergeThirdParty()
    }

    private fun mergeThirdParty(): Map<String, List<Badge>> {
        val merged = HashMap<String, MutableList<Badge>>()
        listOfNotNull(chatterinoBadges, supporterBadges).forEach { source ->
            source.forEach { (user, badges) -> merged.getOrPut(user) { ArrayList(badges.size) } += badges }
        }
        return merged
    }

    private fun List<HelixBadgeSet>.toMap(): Map<String, Badge> {
        val map = HashMap<String, Badge>()
        for (set in this) for (v in set.versions) {
            map["${set.setId}/${v.id}"] = Badge(v.url2x.ifEmpty { v.url1x }, v.title, BadgeProvider.Twitch)
        }
        return map
    }

    private companion object {
        const val TAG = "BadgeRepository"

        /** How long after a failed fetch it is worth asking again. */
        const val RETRY_AFTER_MS = 5 * 60_000L

        /** The supporter badge ships with the app, so Coil loads it from the resources. */
        const val SUPPORTER_BADGE_URL = "android.resource://dev.chatter.app/drawable/ic_badge_supporter"
    }
}
