package dev.chatter.app.emotes

import android.util.Log
import dev.chatter.app.chat.EmoteSource
import dev.chatter.app.net.BttvEmote
import dev.chatter.app.net.FfzEmote
import dev.chatter.app.net.ServiceTrouble
import dev.chatter.app.net.SevenTvActiveEmote
import dev.chatter.app.net.ThirdPartyEmoteApi
import dev.chatter.app.net.TrustedImages
import dev.chatter.app.net.TwitchEmoteApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * All emotes the app knows, keyed by the exact word that triggers them. Lookups are plain map gets,
 * so parsing a message is O(words).
 */
class EmoteRepository(
    private val helix: TwitchEmoteApi,
    private val thirdParty: ThirdPartyEmoteApi,
    private val trouble: ServiceTrouble = ServiceTrouble(),
    /** Injected for tests. */
    private val now: () -> Long = System::currentTimeMillis,
) : EmoteSource {
    @Volatile private var global: ProviderEmotes = ProviderEmotes()
    @Volatile private var twitchUser: Map<String, Emote> = emptyMap()
    private val channels = ConcurrentHashMap<String, ProviderEmotes>()
    /** Twitch follower emotes the user may use, by channel id. */
    private val channelTwitch = ConcurrentHashMap<String, Map<String, Emote>>()

    /** When a channel last loaded from all three providers; see [refreshChannel]. */
    private val lastFullLoad = ConcurrentHashMap<String, Long>()

    /** 7TV ids per Twitch channel id, for EventAPI live updates. */
    private val sevenTvSets = ConcurrentHashMap<String, String>()
    private val sevenTvUsers = ConcurrentHashMap<String, String>()
    private val globalLock = Mutex()

    /**
     * Providers whose global emotes are missing: all of them before the first load, afterwards the
     * ones that did not answer. [retryMissing] asks only these.
     */
    @Volatile private var globalMissing: Set<EmoteProvider> = THIRD_PARTY

    /**
     * False until global emotes were requested at all, so an app that has not started loading does
     * not count as waiting for a provider.
     */
    @Volatile private var globalAsked = false

    /** The same per channel. */
    private val channelMissing = ConcurrentHashMap<String, Set<EmoteProvider>>()

    /** Increments on every change, so the picker and autocomplete can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _waitingForProvider = MutableStateFlow(false)
    /** True while a provider still owes emotes. */
    val waitingForProvider: StateFlow<Boolean> = _waitingForProvider

    private val _recovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * A previously unreachable provider answered. Messages on screen were built without its emotes
     * and are rebuilt.
     */
    val recovered: SharedFlow<Unit> = _recovered

    /** Third-party emote for a word, channel before global. */
    override fun lookup(channelId: String?, word: String): Emote? =
        channelId?.let { channels[it]?.byName?.get(word) } ?: global.byName[word]

    /** Twitch emote the logged-in user owns, for rendering their own echoed messages. */
    override fun lookupOwnTwitch(channelId: String?, word: String): Emote? =
        twitchUser[word] ?: channelId?.let { channelTwitch[it]?.get(word) }

    /** All providers have answered for [channelId], so a message built now will not change. */
    override fun complete(channelId: String?): Boolean =
        globalMissing.isEmpty() && (channelId == null || channelMissing[channelId].isNullOrEmpty())

    /**
     * Everything the user can type in a channel, for autocomplete and the picker. Weaker sources go
     * in first and are overwritten (global before channel, third party before Twitch), the same
     * order a message is rendered in.
     */
    fun available(channelId: String?): List<Emote> {
        val result = LinkedHashMap<String, Emote>()
        global.byName.values.forEach { result[it.name] = it }
        channelId?.let { channels[it] }?.byName?.values?.forEach { result[it.name] = it }
        twitchUser.values.forEach { result[it.name] = it }
        channelId?.let { channelTwitch[it] }?.values?.forEach { result[it.name] = it }
        return result.values.toList()
    }

    /** Asks the providers that still owe global emotes; no request once all are loaded. */
    suspend fun loadGlobal() = globalLock.withLock {
        globalAsked = true
        val wanted = globalMissing
        if (wanted.isEmpty()) return@withLock
        val loaded = global.merge(ask(wanted) { fetchGlobal(it) })
        global = loaded.emotes
        globalMissing = loaded.failed
        updateWaiting()
        _version.update { it + 1 }
    }

    suspend fun loadTwitchUserEmotes(userId: String) {
        val list = safe("Twitch user emotes") { helix.userEmotes(userId) } ?: return
        twitchUser = list.associate { it.name to Emote(it.name, it.id, twitchEmoteUrl(it.id), EmoteProvider.Twitch, isChannel = it.emoteType != "globals" && it.emoteType != "smilies") }
        _version.update { it + 1 }
    }

    /**
     * Third-party channel emotes, plus the channel's Twitch follower emotes if [userId] follows.
     * [wanted] limits the load to some providers; [retryMissing] uses it to ask only the silent
     * ones.
     */
    suspend fun loadChannel(
        channelId: String,
        userId: String?,
        wanted: Set<EmoteProvider> = THIRD_PARTY,
    ) = coroutineScope {
        val follower = async { if (userId != null) loadFollowerEmotes(channelId, userId) }
        val answers = ask(wanted) { fetchChannel(channelId, it) }
        // Merged into the current emotes once the answers are in, not into a snapshot from before
        // the request. Otherwise a 7TV change or another provider's retry finishing in between is
        // lost.
        var failed = emptySet<EmoteProvider>()
        channels.compute(channelId) { _, current ->
            (current ?: ProviderEmotes()).merge(answers).also { failed = it.failed }.emotes
        }
        // Providers not asked this time keep their previous state.
        val missing = channelMissing.compute(channelId) { _, before -> before.orEmpty() - wanted + failed }.orEmpty()
        if (missing.isEmpty()) lastFullLoad[channelId] = now() else lastFullLoad.remove(channelId)
        updateWaiting()
        follower.await()
        _version.update { it + 1 }
    }

    /**
     * Asks only the providers that did not answer. Returns true if one of them is back, so messages
     * built without its emotes can be rebuilt.
     */
    suspend fun retryMissing(userId: String?): Boolean {
        var recovered = false
        if (globalAsked && globalMissing.isNotEmpty()) {
            val before = globalMissing
            loadGlobal()
            if (globalMissing != before) recovered = true
        }
        // Over a copy: loading writes into this map.
        channelMissing.entries.map { it.key to it.value }.forEach { (channelId, missing) ->
            if (missing.isEmpty()) return@forEach
            loadChannel(channelId, userId, missing)
            if (channelMissing[channelId].orEmpty() != missing) recovered = true
        }
        if (recovered) _recovered.tryEmit(Unit)
        return recovered
    }

    /**
     * Updates a channel's emotes after the app was away, unless they were fully loaded recently.
     * Emote sets rarely change and 7TV pushes its changes anyway, so asking every provider of every
     * channel on each return would be a lot of requests for nothing. A failed load is retried at
     * once.
     */
    suspend fun refreshChannel(channelId: String) {
        val last = lastFullLoad[channelId]
        if (last != null && now() - last < RELOAD_AFTER_MS) return
        loadChannel(channelId, null)
    }

    private suspend fun loadFollowerEmotes(channelId: String, userId: String) {
        val emotes = safe("Twitch channel emotes") { helix.channelEmotes(channelId) }
            ?.filter { it.emoteType == "follower" }
            ?.takeIf { it.isNotEmpty() } ?: return
        // The broadcaster can always use their own emotes.
        val usable = userId == channelId || safe("follow status") { helix.isFollowing(userId, channelId) } == true
        if (usable) {
            channelTwitch[channelId] = emotes.associate { it.name to Emote(it.name, it.id, twitchEmoteUrl(it.id), EmoteProvider.Twitch, isChannel = true) }
        }
    }

    /** EventAPI subscriptions: set changes and set switches of every channel. */
    fun sevenTvSubscriptions(): Set<SevenTvSubscription> =
        sevenTvSets.values.mapTo(HashSet()) { SevenTvSubscription.ofObject("emote_set.update", it) } +
            sevenTvUsers.values.map { SevenTvSubscription.ofObject("user.update", it) }

    fun channelForSevenTvSet(setId: String): String? = sevenTvSets.entries.firstOrNull { it.value == setId }?.key
    fun channelForSevenTvUser(userId: String): String? = sevenTvUsers.entries.firstOrNull { it.value == userId }?.key

    /** Applies a pushed 7TV set change. Returns the added emotes. */
    fun applySevenTvUpdate(channelId: String, event: SevenTvEvent.EmoteSetUpdate): List<Emote> {
        val added = event.added.mapNotNull { it.toEmote(true) }
        // Atomic with other changes to the channel's emotes; see loadChannel.
        channels.compute(channelId) { _, existing ->
            val current = existing ?: ProviderEmotes()
            // Only the 7TV map changes, so a name another provider owns keeps resolving to it.
            val stv = HashMap(current.sevenTv)
            event.removed.forEach { stv.remove(it.name) }
            event.renamed.forEach { (old, new) ->
                val before = stv.remove(old.name)
                (new.toEmote(true) ?: before?.copy(name = new.name))?.let { stv[new.name] = it }
            }
            added.forEach { stv[it.name] = it }
            current.withSevenTv(stv)
        }
        _version.update { it + 1 }
        return added
    }

    fun clear() {
        channels.clear()
        channelMissing.clear()
        updateWaiting()
        lastFullLoad.clear()
        sevenTvSets.clear()
        sevenTvUsers.clear()
        channelTwitch.clear()
        twitchUser = emptyMap()
    }

    /** Asks [wanted] concurrently; null for a provider that did not answer. */
    private suspend fun ask(
        wanted: Set<EmoteProvider>,
        fetch: suspend (EmoteProvider) -> List<Emote>?,
    ): Map<EmoteProvider, List<Emote>?> = coroutineScope {
        wanted.map { provider -> async { provider to fetch(provider) } }.awaitAll().toMap()
    }

    private suspend fun fetchGlobal(provider: EmoteProvider): List<Emote>? = when (provider) {
        EmoteProvider.Ffz -> answer(provider) {
            thirdParty.ffzGlobal()
                .let { g -> g.defaultSets.flatMap { g.sets[it.toString()]?.emoticons.orEmpty() } }
                .mapNotNull { it.toEmote(false) }
        }
        EmoteProvider.Bttv -> answer(provider) { thirdParty.bttvGlobal().map { it.toEmote(false) } }
        EmoteProvider.SevenTv -> answer(provider) {
            thirdParty.sevenTvGlobal().emotes.orEmpty().mapNotNull { it.toEmote(false) }
        }
        // Twitch emotes come from Helix and belong to the account.
        EmoteProvider.Twitch -> null
    }

    private suspend fun fetchChannel(channelId: String, provider: EmoteProvider): List<Emote>? = when (provider) {
        EmoteProvider.Ffz -> answer(provider) {
            thirdParty.ffzChannel(channelId)?.sets?.values?.flatMap { it.emoticons }?.mapNotNull { it.toEmote(true) }.orEmpty()
        }
        EmoteProvider.Bttv -> answer(provider) {
            thirdParty.bttvChannel(channelId)?.let { it.channelEmotes + it.sharedEmotes }?.map { it.toEmote(true) }.orEmpty()
        }
        EmoteProvider.SevenTv -> answer(provider) {
            val user = thirdParty.sevenTvChannel(channelId)
            user?.emoteSet?.id?.takeIf { it.isNotEmpty() }?.let { sevenTvSets[channelId] = it } ?: sevenTvSets.remove(channelId)
            user?.user?.id?.takeIf { it.isNotEmpty() }?.let { sevenTvUsers[channelId] = it } ?: sevenTvUsers.remove(channelId)
            user?.emoteSet?.emotes.orEmpty().mapNotNull { it.toEmote(true) }
        }
        EmoteProvider.Twitch -> null
    }

    /** One provider's answer, or null. [ServiceTrouble] reports the outage once per run. */
    private suspend fun <T> answer(provider: EmoteProvider, block: suspend () -> T): T? = try {
        block().also { trouble.reachable(provider.label) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Loading ${provider.label} failed: ${e.message}")
        trouble.report(provider.label)
        null
    }

    private fun updateWaiting() {
        _waitingForProvider.value = (globalAsked && globalMissing.isNotEmpty()) ||
            channelMissing.values.any { it.isNotEmpty() }
    }

    private suspend fun <T> safe(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Loading $what failed: ${e.message}")
        null
    }

    private fun BttvEmote.toEmote(channel: Boolean) = Emote(
        name = code, id = id,
        url = "https://cdn.betterttv.net/emote/$id/2x.webp",
        provider = EmoteProvider.Bttv,
        aspectRatio = if (width != null && height != null && height > 0) width.toFloat() / height else 1f,
        sizeKnown = width != null && height != null && height > 0,
        zeroWidth = !channel && code in BTTV_ZERO_WIDTH,
        isChannel = channel,
        author = user?.displayName?.ifEmpty { null },
    )

    /** Null if the picture is not on FFZ's own hosts; see [TrustedImages]. */
    private fun FfzEmote.toEmote(channel: Boolean): Emote? {
        val raw = animated?.let { it["2"] ?: it["1"] } ?: urls["2"] ?: urls["1"] ?: ""
        return Emote(
            name = name, id = id.toString(),
            url = TrustedImages.url(raw) ?: return null,
            provider = EmoteProvider.Ffz,
            aspectRatio = if (height > 0) width.toFloat() / height else 1f,
            isChannel = channel,
            author = owner?.displayName?.ifEmpty { null },
        )
    }

    private fun SevenTvActiveEmote.toEmote(channel: Boolean): Emote? {
        val host = data?.host ?: return null
        val file = host.files.firstOrNull { it.name.startsWith("1x") }
        // The host comes from 7TV; see [TrustedImages].
        val url = TrustedImages.url("${host.url}/2x.webp") ?: return null
        return Emote(
            name = name, id = id,
            url = url,
            provider = EmoteProvider.SevenTv,
            aspectRatio = if (file != null && file.height > 0) file.width.toFloat() / file.height else 1f,
            // Flag 1 on the active emote or 256 on the emote marks it zero-width.
            zeroWidth = (flags and 1) != 0 || (data.flags and 256) != 0,
            isChannel = channel,
            unlisted = !data.listed,
            author = data.owner?.displayName?.ifEmpty { null },
        )
    }

    private companion object {
        const val TAG = "EmoteRepository"

        /** The providers asked for emotes. Twitch is not one of them. */
        val THIRD_PARTY = setOf(EmoteProvider.Ffz, EmoteProvider.Bttv, EmoteProvider.SevenTv)

        /** How long a full load of a channel counts as current. */
        const val RELOAD_AFTER_MS = 15 * 60_000L

        val BTTV_ZERO_WIDTH = setOf(
            "SoSnowy", "IceCold", "SantaHat", "TopHat", "ReinDeer", "CandyCane", "cvMask", "cvHazmat",
        )
    }
}

/**
 * The emotes of one scope (global or a channel), one map per provider.
 *
 * Providers can use the same name for different pictures, e.g. `susge` on BTTV and a Christmas
 * `susge` on 7TV. Like Chatterino and DankChat, FFZ wins over BTTV over 7TV. Keeping the maps apart
 * lets a pushed 7TV change be applied without taking over another provider's name.
 */
internal class ProviderEmotes(
    val ffz: Map<String, Emote> = emptyMap(),
    val bttv: Map<String, Emote> = emptyMap(),
    val sevenTv: Map<String, Emote> = emptyMap(),
) {
    /** What each name resolves to: weaker providers first, overwritten by stronger ones. */
    val byName: Map<String, Emote> = HashMap<String, Emote>(sevenTv.size + bttv.size + ffz.size).apply {
        putAll(sevenTv)
        putAll(bttv)
        putAll(ffz)
    }

    fun withSevenTv(emotes: Map<String, Emote>) = ProviderEmotes(ffz, bttv, emotes)

    /**
     * Takes over a load's results: per asked provider its emotes, or null if it did not answer.
     *
     * A provider that did not answer (null, unlike an empty list) keeps its previous emotes, so an
     * outage does not turn them into plain text. Providers that were not asked keep theirs too.
     */
    fun merge(results: Map<EmoteProvider, List<Emote>?>) = Merged(
        emotes = ProviderEmotes(
            ffz = results[EmoteProvider.Ffz]?.byName() ?: this.ffz,
            bttv = results[EmoteProvider.Bttv]?.byName() ?: this.bttv,
            sevenTv = results[EmoteProvider.SevenTv]?.byName() ?: this.sevenTv,
        ),
        failed = results.filterValues { it == null }.keys,
    )

    /** The emotes to keep, and the providers that were asked and did not answer. */
    class Merged(val emotes: ProviderEmotes, val failed: Set<EmoteProvider>)

    private fun List<Emote>.byName(): Map<String, Emote> = associateBy { it.name }
}
