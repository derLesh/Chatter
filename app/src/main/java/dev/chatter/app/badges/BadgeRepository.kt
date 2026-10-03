package dev.chatter.app.badges

import android.util.Log
import dev.chatter.app.chat.BadgeSource
import dev.chatter.app.net.ChatterSupporter
import dev.chatter.app.net.HelixBadgeSet
import dev.chatter.app.net.NotLoggedInException
import dev.chatter.app.net.ServiceTrouble
import dev.chatter.app.net.ThirdPartyBadgeApi
import dev.chatter.app.net.TrustedImages
import dev.chatter.app.net.TwitchBadgeApi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** Names of the supporter badge, depending on how and how long someone supports. */
class SupporterTitles(
    /** Supported Chatter at some point. */
    val once: String,
    /** Monthly sponsorship, less than a month so far. */
    val monthly: String,
    /** Monthly sponsorship for a number of months, like a subscriber badge. */
    val monthlyFor: (months: Int) -> String,
)

/** Where a badge comes from. Each can be turned off in the settings. */
enum class BadgeProvider { Twitch, SevenTv, Chatterino, Chatter }

data class Badge(val url: String, val title: String, val provider: BadgeProvider = BadgeProvider.Twitch)

/**
 * Resolves the `badges` IRC tag (e.g. "moderator/1,subscriber/12") to images, and adds badges from
 * other clients, which belong to a Twitch user id instead of a tag.
 */
class BadgeRepository(
    private val helix: TwitchBadgeApi,
    private val thirdParty: ThirdPartyBadgeApi,
    /** Where unreachable lists are reported. */
    private val trouble: ServiceTrouble = ServiceTrouble(),
    /** Injected for tests. */
    private val now: () -> Long = System::currentTimeMillis,
) : BadgeSource {
    @Volatile private var global: Map<String, Badge> = emptyMap()
    private val channels = ConcurrentHashMap<String, Map<String, Badge>>()

    /** Third-party badges by Twitch user id; a user can have several. */
    @Volatile private var thirdPartyBadges: Map<String, List<Badge>> = emptyMap()

    /**
     * The two lists behind [thirdPartyBadges], each null until fetched. Kept apart so one that
     * failed can be fetched alone later.
     */
    @Volatile private var chatterinoBadges: Map<String, List<Badge>>? = null
    @Volatile private var supporterBadges: Map<String, List<Badge>>? = null

    /**
     * 7TV badges. There is no list to fetch since the cosmetics endpoint was retired; they arrive
     * over the EventAPI as a badge description ([sevenTvBadge]) and, separately, who wears it
     * ([sevenTvWearer]). Chatterino does the same.
     */
    private val sevenTvCosmetics = ConcurrentHashMap<String, Badge>()

    /** Twitch user id to the cosmetic they wear; 7TV shows one badge per person. */
    private val sevenTvWearers = ConcurrentHashMap<String, String>()

    /** Channels whose badges failed to load, retried when the app comes back. */
    private val failedChannels = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var lastRetry = 0L

    /** Providers whose badges are shown. Set from the settings, read for every message. */
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

    /** Someone started or stopped wearing a badge, by Twitch user id. */
    fun sevenTvWearer(userId: String, cosmeticId: String, worn: Boolean) {
        if (worn) sevenTvWearers[userId] = cosmeticId
        else sevenTvWearers.remove(userId, cosmeticId)
    }

    suspend fun loadGlobal() {
        if (global.isNotEmpty()) return
        runCatching { helix.globalBadges() }
            .onSuccess {
                global = it.toMap()
                trouble.reachable(ServiceTrouble.TWITCH)
            }
            .onFailure {
                if (it !is NotLoggedInException) trouble.report(ServiceTrouble.TWITCH)
                Log.w(TAG, "Global badges failed: ${it.message}")
            }
    }

    suspend fun loadChannel(channelId: String) {
        runCatching { helix.channelBadges(channelId) }
            .onSuccess {
                channels[channelId] = it.toMap()
                failedChannels.remove(channelId)
            }
            .onFailure {
                failedChannels.add(channelId)
                if (it !is NotLoggedInException) trouble.report(ServiceTrouble.TWITCH)
                Log.w(TAG, "Channel badges failed: ${it.message}")
            }
    }

    /**
     * Fetches what failed to load earlier: the global set, the other clients' lists and unreachable
     * channels. Loaded data is left alone, so the usual return to the app costs nothing.
     */
    suspend fun retryMissing(supporterTitles: SupporterTitles?) {
        // Throttled: a provider that is down stays down for a while, and the app is opened often.
        val at = now()
        if (at - lastRetry < RETRY_AFTER_MS) return
        lastRetry = at
        loadGlobal()
        loadThirdParty(supporterTitles)
        failedChannels.toList().forEach { loadChannel(it) }
    }

    /**
     * Chatterino's badge list and Chatter's supporters. One request each for all users, then looked
     * up by user id. Only missing lists are fetched, so one that failed at start is picked up
     * later. 7TV badges arrive over the EventAPI.
     */
    suspend fun loadThirdParty(supporterTitles: SupporterTitles?) {
        var changed = false

        if (chatterinoBadges == null) {
            runCatching { thirdParty.chatterinoBadges() }
                .onSuccess { list ->
                    val byUser = HashMap<String, MutableList<Badge>>()
                    for (badge in list.badges) {
                        // Only from Chatterino's own host; see TrustedImages.
                        val url = TrustedImages.url(badge.image2.ifEmpty { badge.image1 }.ifEmpty { badge.image3 }) ?: continue
                        val image = Badge(url, badge.tooltip, BadgeProvider.Chatterino)
                        badge.users.forEach { byUser.getOrPut(it) { ArrayList(1) } += image }
                    }
                    chatterinoBadges = byUser
                    changed = true
                }
                .onFailure {
                    trouble.report(ServiceTrouble.CHATTERINO)
                    Log.w(TAG, "Chatterino badges failed: ${it.message}")
                }
        }

        // Null while sponsoring is off; there is no list to fetch then.
        if (supporterTitles != null && supporterBadges == null) {
            runCatching { thirdParty.chatterSupporters() }
                .onSuccess { list ->
                    supporterBadges = list.supporters
                        .filter { it.twitch.isNotEmpty() }
                        .associate { it.twitch to listOf(supporterBadge(it, supporterTitles)) }
                    changed = true
                }
                .onFailure {
                    trouble.report(ServiceTrouble.SUPPORTERS)
                    Log.w(TAG, "Supporter list failed: ${it.message}")
                }
        }

        if (changed) thirdPartyBadges = mergeThirdParty()
    }

    /**
     * The supporter's badge. A running monthly sponsorship shows its length; everything else,
     * including unreadable dates or unknown fields, gets the plain badge.
     */
    private fun supporterBadge(supporter: ChatterSupporter, titles: SupporterTitles): Badge {
        val since = supporter.monthlySince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val months = since?.let { ChronoUnit.MONTHS.between(it, today()) }?.toInt() ?: -1
        return Badge(
            url = SUPPORTER_BADGE_URL,
            title = when {
                since == null -> titles.once
                months < 1 -> titles.monthly
                else -> titles.monthlyFor(months)
            },
            provider = BadgeProvider.Chatter,
        )
    }

    private fun today(): LocalDate =
        Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate()

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
            val url = TrustedImages.url(v.url2x.ifEmpty { v.url1x }) ?: continue
            map["${set.setId}/${v.id}"] = Badge(url, v.title, BadgeProvider.Twitch)
        }
        return map
    }

    private companion object {
        const val TAG = "BadgeRepository"

        /** Wait after a failed fetch before trying again. */
        const val RETRY_AFTER_MS = 5 * 60_000L

        /** Ships with the app; Coil loads it from the resources. */
        const val SUPPORTER_BADGE_URL = "android.resource://dev.chatter.app/drawable/ic_badge_supporter"
    }
}
