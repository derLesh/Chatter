package me.lesh.chatter.badges

import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.chat.AssetChanges
import me.lesh.chatter.chat.BadgeSource
import me.lesh.chatter.net.ChatterBadgeApi
import me.lesh.chatter.net.HelixBadgeSet
import me.lesh.chatter.net.NotLoggedInException
import me.lesh.chatter.net.ServiceTrouble
import me.lesh.chatter.net.ThirdPartyBadgeApi
import me.lesh.chatter.net.TrustedImages
import me.lesh.chatter.net.TwitchBadgeApi
import me.lesh.chatter.util.runCatchingCancellable
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/** Where a badge comes from. Each can be turned off in the settings. */
enum class BadgeProvider {
    Twitch, SevenTv, Ffz, Chatterino, Chatter;

    companion object {
        /** The providers there were before FFZ. A list of the ones turned on from then names only these. */
        val BEFORE_FFZ = setOf(Twitch, SevenTv, Chatterino, Chatter)
    }
}

/**
 * One badge picture. [set] names its kind within the provider, the same for every version of it:
 * Twitch's set id ("subscriber", "predictions"), 7TV's cosmetic id, FFZ's badge name, Chatterino's
 * tooltip.
 */
data class Badge(
    val url: String,
    val title: String,
    val provider: BadgeProvider = BadgeProvider.Twitch,
    val set: String = "",
    /** A picture for light themes, where the provider has one; [url] is then the dark one. */
    val urlOnLight: String? = null,
    /** Drawn behind the picture, as ARGB; FFZ's pictures are white symbols on nothing. */
    val background: Int? = null,
) {
    /** What hiding a kind of badge matches; see [HiddenBadge]. */
    val kind: String = kindOf(provider, set)

    companion object {
        fun kindOf(provider: BadgeProvider, set: String) = "${provider.name}/$set"
    }
}

/**
 * Resolves the `badges` IRC tag (e.g. "moderator/1,subscriber/12") to images, and adds badges from
 * other clients, which belong to a Twitch user id instead of a tag.
 */
class BadgeRepository(
    private val helix: TwitchBadgeApi,
    private val thirdParty: ThirdPartyBadgeApi,
    private val chatter: ChatterBadgeApi,
    /** What the supporter badge says when touched, in the app's language. */
    private val supporterTitle: () -> String,
    /** Where unreachable lists are reported. */
    private val trouble: ServiceTrouble = ServiceTrouble(),
    /** Injected for tests. */
    private val now: () -> Long = System::currentTimeMillis,
) : BadgeSource {
    @Volatile private var global: Map<String, Badge> = emptyMap()
    private val channels = ConcurrentHashMap<String, Map<String, Badge>>()
    private val channelLock = Any()
    private val channelLifetimes = HashMap<String, Any>()

    /** Third-party badges by Twitch user id; a user can have several. */
    @Volatile private var thirdPartyBadges: Map<String, List<Badge>> = emptyMap()

    /**
     * The two lists behind [thirdPartyBadges], each null until fetched. Kept apart so one that
     * failed can be fetched alone later.
     */
    @Volatile private var chatterinoBadges: Map<String, List<Badge>>? = null
    @Volatile private var supporterBadges: Map<String, List<Badge>>? = null

    /**
     * FFZ's badges, each with its wearers. Not in [thirdPartyBadges]: FFZ lists tens of thousands of
     * supporters, and an entry per person would take megabytes. Null until fetched.
     */
    @Volatile private var ffzBadges: List<WornBy>? = null

    /**
     * 7TV badges. There is no list to fetch since the cosmetics endpoint was retired; they arrive
     * over the EventAPI as a badge description ([sevenTvBadge]) and, separately, who wears it
     * ([sevenTvWearer]). Chatterino does the same.
     */
    private val sevenTvCosmetics = RecentDefinitions<Badge>(MAX_DEFINITIONS) { sevenTvWearers.cosmeticIds() }

    /** Twitch user id to the cosmetic they wear; 7TV shows one badge per person. */
    private val sevenTvWearers = RecentWearers(MAX_WEARERS)

    /** 7TV paints by id, and who wears which, arriving like the badges. */
    private val sevenTvPaints = RecentDefinitions<NamePaint>(MAX_DEFINITIONS) { sevenTvPaintWearers.cosmeticIds() }
    private val sevenTvPaintWearers = RecentWearers(MAX_WEARERS)

    /** Channels whose badges failed to load, retried when the app comes back. */
    private val failedChannels = ConcurrentHashMap.newKeySet<String>()

    /** Whether the global set and each channel's were asked for once, answered or not. */
    @Volatile private var globalAnswered = false
    private val answeredChannels = ConcurrentHashMap.newKeySet<String>()

    private val changes = AssetChanges()
    /** Latest arrivals by Twitch channel id; null means global badges. */
    val arrived = changes.arrived

    override fun revision(channelId: String?): Long = changes.revision(channelId)

    @Volatile private var lastRetry = 0L

    /**
     * One badge of every kind loaded so far, for the list in the settings: Twitch's global set and
     * those of the joined channels, Chatterino's and FFZ's lists, the 7TV badges described and the
     * supporter.
     */
    fun knownKinds(): List<Badge> {
        val byKind = LinkedHashMap<String, Badge>()
        // Sorted by key, so a set shows its first version ("subscriber/0") whatever the map order.
        val twitch = global.entries.sortedBy { it.key }.map { it.value } +
            channels.values.flatMap { set -> set.entries.sortedBy { it.key }.map { it.value } }
        val ffz = ffzBadges.orEmpty().map { it.badge }
        (twitch + thirdPartyBadges.values.flatten() + ffz + sevenTvCosmetics.values).forEach { byKind.putIfAbsent(it.kind, it) }
        return byKind.values.toList()
    }

    /** Providers whose badges are shown. Set from the settings, read for every message. */
    @Volatile var enabled: Set<BadgeProvider> = BadgeProvider.entries.toSet()

    override fun resolve(channelId: String?, badgesTag: String?, userId: String?): List<Badge> {
        val providers = enabled
        val twitch = if (badgesTag.isNullOrEmpty() || BadgeProvider.Twitch !in providers) emptyList() else {
            val channel = channelId?.let { channels[it] }
            badgesTag.split(',').mapNotNull { key -> channel?.get(key) ?: global[key] }
        }
        val extra = userId?.let { thirdPartyBadges[it] }?.filter { it.provider in providers }.orEmpty()
        val ffz = if (userId == null || BadgeProvider.Ffz !in providers) emptyList() else ffzBadgesOf(userId)
        val sevenTv = userId?.let(::sevenTvBadgeOf)
        if (extra.isEmpty() && ffz.isEmpty() && sevenTv == null) return twitch
        return twitch + extra + ffz + listOfNotNull(sevenTv)
    }

    private fun ffzBadgesOf(userId: String): List<Badge> {
        val worn = ffzBadges ?: return emptyList()
        val id = userId.toLongOrNull() ?: return emptyList()
        return worn.filter { Arrays.binarySearch(it.userIds, id) >= 0 }.map { it.badge }
    }

    override fun complete(channelId: String?): Boolean =
        globalAnswered && (channelId == null || channelId in answeredChannels)

    override fun paint(userId: String?): NamePaint? =
        if (userId == null || BadgeProvider.SevenTv !in enabled) null
        else sevenTvPaintWearers[userId]?.let { sevenTvPaints[it] }

    /** A paint 7TV described over the EventAPI. */
    fun sevenTvPaint(paint: NamePaint) {
        sevenTvPaints[paint.id] = paint
    }

    /** Someone started or stopped wearing a paint, by Twitch user id. */
    fun sevenTvPaintWearer(userId: String, paintId: String, worn: Boolean) {
        if (worn) sevenTvPaintWearers[userId] = paintId
        else sevenTvPaintWearers.remove(userId, paintId)
    }

    /** The 7TV badge [userId] wears, while 7TV badges are shown. */
    fun sevenTvBadgeOf(userId: String): Badge? =
        if (BadgeProvider.SevenTv !in enabled) null else sevenTvWearers[userId]?.let { sevenTvCosmetics[it] }

    /** A badge 7TV described over the EventAPI. */
    fun sevenTvBadge(id: String, name: String, tooltip: String) {
        sevenTvCosmetics[id] = Badge(
            url = "https://cdn.7tv.app/badge/$id/2x.webp",
            title = tooltip.ifEmpty { name },
            provider = BadgeProvider.SevenTv,
            set = id,
        )
    }

    /** Someone started or stopped wearing a badge, by Twitch user id. */
    fun sevenTvWearer(userId: String, cosmeticId: String, worn: Boolean) {
        if (worn) sevenTvWearers[userId] = cosmeticId
        else sevenTvWearers.remove(userId, cosmeticId)
    }

    suspend fun loadGlobal() {
        if (global.isNotEmpty()) return
        runCatchingCancellable { helix.globalBadges() }
            .onSuccess {
                val loaded = it.toMap()
                val changed = global != loaded
                global = loaded
                trouble.reachable(ServiceTrouble.TWITCH)
                if (changed) changes.changed()
            }
            .onFailure {
                if (it !is NotLoggedInException) trouble.report(ServiceTrouble.TWITCH)
                AppLog.w(TAG, "Global badges failed: ${it.message}")
            }
        globalAnswered = true
    }

    suspend fun loadChannel(channelId: String) = loadChannel(channelId, expectedLifetime = null)

    private suspend fun loadChannel(channelId: String, expectedLifetime: Any?) {
        val lifetime = synchronized(channelLock) {
            if (expectedLifetime != null && channelLifetimes[channelId] !== expectedLifetime) return
            channelLifetimes.getOrPut(channelId) { Any() }
        }
        val result = runCatchingCancellable { helix.channelBadges(channelId) }
        synchronized(channelLock) {
            if (channelLifetimes[channelId] !== lifetime) return
            result.onSuccess {
                val loaded = it.toMap()
                val changed = channels.put(channelId, loaded) != loaded
                failedChannels.remove(channelId)
                if (changed) changes.changed(channelId)
            }.onFailure {
                failedChannels.add(channelId)
                if (it !is NotLoggedInException) trouble.report(ServiceTrouble.TWITCH)
                AppLog.w(TAG, "Channel badges failed: ${it.message}")
            }
            answeredChannels.add(channelId)
        }
    }

    fun removeChannel(channelId: String) = synchronized(channelLock) {
        channelLifetimes.remove(channelId)
        channels.remove(channelId)
        changes.forget(channelId)
        failedChannels.remove(channelId)
        answeredChannels.remove(channelId)
    }

    /**
     * Fetches what failed to load earlier: the global set, the other clients' lists and unreachable
     * channels. Loaded data is left alone, so the usual return to the app costs nothing.
     */
    suspend fun retryMissing() {
        // Throttled: a provider that is down stays down for a while, and the app is opened often.
        val at = now()
        if (at - lastRetry < RETRY_AFTER_MS) return
        lastRetry = at
        loadGlobal()
        loadThirdParty()
        val pending = synchronized(channelLock) { failedChannels.mapNotNull { id -> channelLifetimes[id]?.let { id to it } } }
        pending.forEach { (id, lifetime) -> loadChannel(id, expectedLifetime = lifetime) }
    }

    /**
     * Chatterino's and FFZ's badge lists and Chatter's supporters. One request each for all users,
     * then looked up by user id. Only missing lists are fetched, so one that failed at start is
     * picked up later. 7TV badges arrive over the EventAPI.
     */
    suspend fun loadThirdParty() {
        var changed = false

        if (chatterinoBadges == null) {
            runCatchingCancellable { thirdParty.chatterinoBadges() }
                .onSuccess { list ->
                    val byUser = HashMap<String, MutableList<Badge>>()
                    for (badge in list.badges) {
                        // Only from Chatterino's own host; see TrustedImages.
                        val url = TrustedImages.url(badge.image2.ifEmpty { badge.image1 }.ifEmpty { badge.image3 }) ?: continue
                        val image = Badge(url, badge.tooltip, BadgeProvider.Chatterino, set = badge.tooltip)
                        badge.users.forEach { byUser.getOrPut(it) { ArrayList(1) } += image }
                    }
                    chatterinoBadges = byUser
                    changed = true
                }
                .onFailure {
                    trouble.report(ServiceTrouble.CHATTERINO)
                    AppLog.w(TAG, "Chatterino badges failed: ${it.message}")
                }
        }

        if (ffzBadges == null) {
            runCatchingCancellable { thirdParty.ffzBadges() }
                .onSuccess { list ->
                    ffzBadges = list.badges.mapNotNull { badge ->
                        val urls = badge.urls
                        // Only from FFZ's own host; see TrustedImages.
                        val url = (urls["2"] ?: urls["1"] ?: urls["4"])?.let(TrustedImages::url) ?: return@mapNotNull null
                        val userIds = list.users[badge.id.toString()]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val image = Badge(
                            url, badge.title.ifEmpty { badge.name }, BadgeProvider.Ffz,
                            set = badge.name.ifEmpty { badge.id.toString() }, background = cssColor(badge.color),
                        )
                        WornBy(image, userIds.also { it.sort() })
                    }
                    trouble.reachable(ServiceTrouble.FFZ)
                    changed = true
                }
                .onFailure {
                    trouble.report(ServiceTrouble.FFZ)
                    AppLog.w(TAG, "FFZ badges failed: ${it.message}")
                }
        }

        if (supporterBadges == null) {
            runCatchingCancellable { loadSupporters() }
                .onSuccess {
                    supporterBadges = it
                    trouble.reachable(ServiceTrouble.CHATTER_API)
                    changed = true
                }
                .onFailure {
                    trouble.report(ServiceTrouble.CHATTER_API)
                    AppLog.w(TAG, "Supporter list failed: ${it.message}")
                }
        }

        if (changed) {
            thirdPartyBadges = mergeThirdParty()
            changes.changed()
        }
    }

    /** Who supports Chatter, each wearing the badge the API serves for them. */
    private suspend fun loadSupporters(): Map<String, List<Badge>> {
        val supporters = chatter.supporters().supporters
        val images = chatter.badges().badges.firstOrNull { it.id == SUPPORTER_BADGE_ID }?.images.orEmpty()
        fun picture(theme: String) = images[theme]?.let { it["2x"] ?: it["1x"] ?: it["4x"] }?.let(chatter::imageUrl)
        val onLight = picture("light")
        val onDark = picture("dark") ?: onLight ?: throw IOException("no supporter badge among Chatter's badges")
        val badge = listOf(Badge(onDark, supporterTitle(), BadgeProvider.Chatter, SUPPORTER_SET, urlOnLight = onLight))
        return supporters.filter { it.isNotEmpty() }.associateWith { badge }
    }

    private fun mergeThirdParty(): Map<String, List<Badge>> {
        val merged = HashMap<String, MutableList<Badge>>()
        listOfNotNull(chatterinoBadges, supporterBadges).forEach { source ->
            source.forEach { (user, badges) -> merged.getOrPut(user) { ArrayList(badges.size) } += badges }
        }
        return merged
    }

    /** A badge and the sorted Twitch user ids of who wears it, searched instead of mapped. */
    private class WornBy(val badge: Badge, val userIds: LongArray)

    private fun List<HelixBadgeSet>.toMap(): Map<String, Badge> {
        val map = HashMap<String, Badge>()
        for (set in this) for (v in set.versions) {
            val url = TrustedImages.url(v.url2x.ifEmpty { v.url1x }) ?: continue
            map["${set.setId}/${v.id}"] = Badge(url, v.title, BadgeProvider.Twitch, set.setId)
        }
        return map
    }

    private companion object {
        const val TAG = "BadgeRepository"

        /** Wait after a failed fetch before trying again. */
        const val RETRY_AFTER_MS = 5 * 60_000L

        /** Per map, a few hundred kilobytes; far more chatters than a busy evening shows. */
        const val MAX_WEARERS = 5_000

        /** Enough for every tracked wearer plus definitions awaiting their wearer event. */
        const val MAX_DEFINITIONS = MAX_WEARERS + 512

        /** The supporter badge's id in Chatter's badge list. */
        const val SUPPORTER_BADGE_ID = "chatter"

        const val SUPPORTER_SET = "supporter"
    }
}

/** "#RRGGBB" as an opaque ARGB colour; anything else is no colour. */
internal fun cssColor(raw: String?): Int? {
    val hex = raw?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
}
