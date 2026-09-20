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

    /** Which providers' badges are shown. Set from the settings, read on every message. */
    @Volatile var enabled: Set<BadgeProvider> = BadgeProvider.entries.toSet()

    override fun resolve(channelId: String?, badgesTag: String?, userId: String?): List<Badge> {
        val providers = enabled
        val twitch = if (badgesTag.isNullOrEmpty() || BadgeProvider.Twitch !in providers) emptyList() else {
            val channel = channelId?.let { channels[it] }
            badgesTag.split(',').mapNotNull { key -> channel?.get(key) ?: global[key] }
        }
        val extra = userId?.let { thirdPartyBadges[it] }?.filter { it.provider in providers }.orEmpty()
        return if (extra.isEmpty()) twitch else twitch + extra
    }

    suspend fun loadGlobal() {
        if (global.isNotEmpty()) return
        runCatching { helix.globalBadges() }
            .onSuccess { global = it.toMap() }
            .onFailure { Log.w(TAG, "Global badges failed: ${it.message}") }
    }

    suspend fun loadChannel(channelId: String) {
        runCatching { helix.channelBadges(channelId) }
            .onSuccess { channels[channelId] = it.toMap() }
            .onFailure { Log.w(TAG, "Channel badges failed: ${it.message}") }
    }

    /**
     * The badge lists of the other clients. Both are one request for everybody, so they are
     * fetched once per app start and then only looked up by user id.
     */
    suspend fun loadThirdParty(supporterTitle: String) {
        val merged = HashMap<String, MutableList<Badge>>()

        runCatching { thirdParty.sevenTvCosmetics() }
            .onSuccess { cosmetics ->
                for (badge in cosmetics.badges) {
                    val host = badge.host ?: continue
                    val base = if (host.url.startsWith("//")) "https:${host.url}" else host.url
                    val image = Badge("$base/2x", badge.tooltip.ifEmpty { badge.name }, BadgeProvider.SevenTv)
                    badge.users.forEach { merged.getOrPut(it) { ArrayList(1) } += image }
                }
            }
            .onFailure { Log.w(TAG, "7TV badges failed: ${it.message}") }

        runCatching { thirdParty.chatterinoBadges() }
            .onSuccess { list ->
                for (badge in list.badges) {
                    val url = badge.image2.ifEmpty { badge.image1 }.ifEmpty { badge.image3 }
                    if (url.isEmpty()) continue
                    val image = Badge(url, badge.tooltip, BadgeProvider.Chatterino)
                    badge.users.forEach { merged.getOrPut(it) { ArrayList(1) } += image }
                }
            }
            .onFailure { Log.w(TAG, "Chatterino badges failed: ${it.message}") }

        runCatching { thirdParty.chatterSupporters() }
            .onSuccess { supporters ->
                val badge = Badge(SUPPORTER_BADGE_URL, supporterTitle, BadgeProvider.Chatter)
                supporters.users.forEach { merged.getOrPut(it) { ArrayList(1) } += badge }
            }
            .onFailure { Log.w(TAG, "Supporter list failed: ${it.message}") }

        if (merged.isNotEmpty()) thirdPartyBadges = merged
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

        /** The supporter badge ships with the app, so Coil loads it from the resources. */
        const val SUPPORTER_BADGE_URL = "android.resource://dev.chatter.app/drawable/ic_badge_supporter"
    }
}
