package dev.chatter.app.emotes

import android.util.Log
import dev.chatter.app.chat.EmoteSource
import dev.chatter.app.net.BttvEmote
import dev.chatter.app.net.FfzEmote
import dev.chatter.app.net.ServiceTrouble
import dev.chatter.app.net.SevenTvActiveEmote
import dev.chatter.app.net.ThirdPartyEmoteApi
import dev.chatter.app.net.TwitchEmoteApi
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
 * Holds every emote known to the app, keyed by the exact word that triggers it.
 * Lookups are plain HashMap gets so parsing a message stays O(words).
 */
class EmoteRepository(
    private val helix: TwitchEmoteApi,
    private val thirdParty: ThirdPartyEmoteApi,
    private val trouble: ServiceTrouble = ServiceTrouble(),
    /** Only ever [System.currentTimeMillis]; a test hands in one it can move. */
    private val now: () -> Long = System::currentTimeMillis,
) : EmoteSource {
    @Volatile private var global: ProviderEmotes = ProviderEmotes()
    @Volatile private var twitchUser: Map<String, Emote> = emptyMap()
    private val channels = ConcurrentHashMap<String, ProviderEmotes>()
    /** Twitch follower emotes the user may use in a channel (keyed by channel id). */
    private val channelTwitch = ConcurrentHashMap<String, Map<String, Emote>>()

    /** When a channel's emotes last came back from all three providers, for [refreshChannel]. */
    private val lastFullLoad = ConcurrentHashMap<String, Long>()

    /** 7TV ids per Twitch channel id, for live updates via the 7TV EventAPI. */
    private val sevenTvSets = ConcurrentHashMap<String, String>()
    private val sevenTvUsers = ConcurrentHashMap<String, String>()
    private val globalLock = Mutex()

    /**
     * The providers whose global emotes are not in yet — all of them until the first load, and
     * afterwards whoever did not answer. [retryMissing] asks these and nobody else.
     */
    @Volatile private var globalMissing: Set<EmoteProvider> = THIRD_PARTY

    /**
     * False until the global emotes have been asked for at all, so an app that has not started
     * looking yet does not count as one waiting for a provider to come back.
     */
    @Volatile private var globalAsked = false

    /** The same per channel: who still owes this channel its emotes. */
    private val channelMissing = ConcurrentHashMap<String, Set<EmoteProvider>>()

    /** Increments whenever emote data changes, so UI lists (picker, autocomplete) can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _waitingForProvider = MutableStateFlow(false)
    /** True while a provider owes the app emotes, which is what makes it worth asking again. */
    val waitingForProvider: StateFlow<Boolean> = _waitingForProvider

    private val _recovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * A provider that had been unreachable has answered, and its emotes are in. Whatever is on
     * screen was built without them, so the chat builds those messages again.
     */
    val recovered: SharedFlow<Unit> = _recovered

    /** Third-party emote (channel first, then global) for a word in someone's message. */
    override fun lookup(channelId: String?, word: String): Emote? =
        channelId?.let { channels[it]?.byName?.get(word) } ?: global.byName[word]

    /** Twitch emote the logged-in user owns; used to render our own (locally echoed) messages. */
    override fun lookupOwnTwitch(channelId: String?, word: String): Emote? =
        twitchUser[word] ?: channelId?.let { channelTwitch[it]?.get(word) }

    /** Every provider has had its say here, so a message built now cannot come out differently. */
    override fun complete(channelId: String?): Boolean =
        globalMissing.isEmpty() && (channelId == null || channelMissing[channelId].isNullOrEmpty())

    /**
     * Everything the user can type in the given channel, for autocomplete and the picker.
     *
     * A name only ever means one emote, so the weaker sources go in first and are overwritten:
     * global before channel, third party before Twitch. That is the order a message is rendered
     * in, so the picker shows the picture the chat will show.
     */
    fun available(channelId: String?): List<Emote> {
        val result = LinkedHashMap<String, Emote>()
        global.byName.values.forEach { result[it.name] = it }
        channelId?.let { channels[it] }?.byName?.values?.forEach { result[it.name] = it }
        twitchUser.values.forEach { result[it.name] = it }
        channelId?.let { channelTwitch[it] }?.values?.forEach { result[it.name] = it }
        return result.values.toList()
    }

    /** Asks whoever still owes global emotes, and returns without a request once they are all in. */
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
     * Third-party channel emotes plus the channel's Twitch follower emotes (if [userId] follows).
     *
     * [wanted] narrows the load to a few providers, which is how [retryMissing] comes back to the
     * one that was silent without asking the two that answered all over again.
     */
    suspend fun loadChannel(
        channelId: String,
        userId: String?,
        wanted: Set<EmoteProvider> = THIRD_PARTY,
    ) = coroutineScope {
        val follower = async { if (userId != null) loadFollowerEmotes(channelId, userId) }
        val loaded = (channels[channelId] ?: ProviderEmotes()).merge(ask(wanted) { fetchChannel(channelId, it) })
        channels[channelId] = loaded.emotes
        // Whoever was not asked this time is left however the last load left them.
        val missing = channelMissing[channelId].orEmpty() - wanted + loaded.failed
        channelMissing[channelId] = missing
        if (missing.isEmpty()) lastFullLoad[channelId] = now() else lastFullLoad.remove(channelId)
        updateWaiting()
        follower.await()
        _version.update { it + 1 }
    }

    /**
     * Comes back to the providers that did not answer, and to nobody else. Returns true if one of
     * them has since come back, so that what was built without its emotes can be built again.
     *
     * This is what makes a provider that was down when the app started — or when a channel was
     * joined — put itself right: its emotes appear in the messages already on screen as soon as
     * it answers, instead of the chat staying plain text until the app is started again.
     */
    suspend fun retryMissing(userId: String?): Boolean {
        var recovered = false
        if (globalAsked && globalMissing.isNotEmpty()) {
            val before = globalMissing
            loadGlobal()
            if (globalMissing != before) recovered = true
        }
        // Over a copy: loading writes back into the very map this walks.
        channelMissing.entries.map { it.key to it.value }.forEach { (channelId, missing) ->
            if (missing.isEmpty()) return@forEach
            loadChannel(channelId, userId, missing)
            if (channelMissing[channelId].orEmpty() != missing) recovered = true
        }
        if (recovered) _recovered.tryEmit(Unit)
        return recovered
    }

    /**
     * Brings a channel's emotes up to date after the app was away, and does nothing if they were
     * fully loaded a short while ago.
     *
     * Coming back to the app asks every provider of every joined channel again, which for a few
     * channels is a few dozen requests each time the app is glanced at — and emote sets hardly
     * ever change, 7TV pushes its changes over the EventAPI anyway, and the answer is nearly
     * always "nothing new". A load that failed leaves no mark, so that one is retried at once.
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

    /** What to subscribe to at the 7TV EventAPI: set changes and set switches of every channel. */
    fun sevenTvSubscriptions(): Set<SevenTvSubscription> =
        sevenTvSets.values.mapTo(HashSet()) { SevenTvSubscription.ofObject("emote_set.update", it) } +
            sevenTvUsers.values.map { SevenTvSubscription.ofObject("user.update", it) }

    fun channelForSevenTvSet(setId: String): String? = sevenTvSets.entries.firstOrNull { it.value == setId }?.key
    fun channelForSevenTvUser(userId: String): String? = sevenTvUsers.entries.firstOrNull { it.value == userId }?.key

    /** Applies a pushed 7TV set change to the channel's emotes. Returns the added emotes (converted). */
    fun applySevenTvUpdate(channelId: String, event: SevenTvEvent.EmoteSetUpdate): List<Emote> {
        val current = channels[channelId] ?: ProviderEmotes()
        // Only the channel's 7TV emotes change; a name another provider also has keeps resolving
        // to that provider, exactly as it would after a fresh load.
        val stv = HashMap(current.sevenTv)
        event.removed.forEach { stv.remove(it.name) }
        event.renamed.forEach { (old, new) ->
            val existing = stv.remove(old.name)
            (new.toEmote(true) ?: existing?.copy(name = new.name))?.let { stv[new.name] = it }
        }
        val added = event.added.mapNotNull { it.toEmote(true) }
        added.forEach { stv[it.name] = it }
        channels[channelId] = current.withSevenTv(stv)
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

    /** Asks [wanted] all at once; the answers come back per provider, null for one that did not. */
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
                .map { it.toEmote(false) }
        }
        EmoteProvider.Bttv -> answer(provider) { thirdParty.bttvGlobal().map { it.toEmote(false) } }
        EmoteProvider.SevenTv -> answer(provider) {
            thirdParty.sevenTvGlobal().emotes.orEmpty().mapNotNull { it.toEmote(false) }
        }
        // Twitch emotes come from Helix and belong to the account, not to a provider asked here.
        EmoteProvider.Twitch -> null
    }

    private suspend fun fetchChannel(channelId: String, provider: EmoteProvider): List<Emote>? = when (provider) {
        EmoteProvider.Ffz -> answer(provider) {
            thirdParty.ffzChannel(channelId)?.sets?.values?.flatMap { it.emoticons }?.map { it.toEmote(true) }.orEmpty()
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

    /**
     * One provider's answer, or null if it had none. Saying so is left to [ServiceTrouble], which
     * says it once for the whole run however many channels run into the same outage.
     */
    private suspend fun <T> answer(provider: EmoteProvider, block: suspend () -> T): T? = try {
        block().also { trouble.reachable(provider.label) }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("EmoteRepository", "Loading ${provider.label} failed: ${e.message}")
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
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("EmoteRepository", "Loading $what failed: ${e.message}")
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

    private fun FfzEmote.toEmote(channel: Boolean): Emote {
        val raw = animated?.let { it["2"] ?: it["1"] } ?: urls["2"] ?: urls["1"] ?: ""
        return Emote(
            name = name, id = id.toString(),
            url = if (raw.startsWith("//")) "https:$raw" else raw,
            provider = EmoteProvider.Ffz,
            aspectRatio = if (height > 0) width.toFloat() / height else 1f,
            isChannel = channel,
            author = owner?.displayName?.ifEmpty { null },
        )
    }

    private fun SevenTvActiveEmote.toEmote(channel: Boolean): Emote? {
        val host = data?.host ?: return null
        val file = host.files.firstOrNull { it.name.startsWith("1x") }
        val base = if (host.url.startsWith("//")) "https:${host.url}" else host.url
        return Emote(
            name = name, id = id,
            url = "$base/2x.webp",
            provider = EmoteProvider.SevenTv,
            aspectRatio = if (file != null && file.height > 0) file.width.toFloat() / file.height else 1f,
            // Flag 1 on the active emote or 256 on the emote itself marks it as zero-width.
            zeroWidth = (flags and 1) != 0 || (data.flags and 256) != 0,
            isChannel = channel,
            unlisted = !data.listed,
            author = data.owner?.displayName?.ifEmpty { null },
        )
    }

    private companion object {
        /** The three providers a scope's emotes are asked of. Twitch is not one of them. */
        val THIRD_PARTY = setOf(EmoteProvider.Ffz, EmoteProvider.Bttv, EmoteProvider.SevenTv)

        /** How long a full load of a channel's emotes is taken to be current. */
        const val RELOAD_AFTER_MS = 15 * 60_000L

        val BTTV_ZERO_WIDTH = setOf(
            "SoSnowy", "IceCold", "SantaHat", "TopHat", "ReinDeer", "CandyCane", "cvMask", "cvHazmat",
        )
    }
}

/**
 * The emotes of one scope — global, or one channel — kept one map per provider.
 *
 * Providers hand out the same name for different pictures: a channel can have `susge` on BTTV and
 * a Christmas `susge` on 7TV, and only one of them can be what the word means. Chatterino and
 * DankChat both settle that the same way, FFZ before BTTV before 7TV, and Chatter follows them so
 * a message reads the same whichever client it is read in.
 *
 * Holding the three apart instead of merging them once is what lets a pushed 7TV change be applied
 * without it taking over a name another provider owns.
 */
internal class ProviderEmotes(
    val ffz: Map<String, Emote> = emptyMap(),
    val bttv: Map<String, Emote> = emptyMap(),
    val sevenTv: Map<String, Emote> = emptyMap(),
) {
    /** What each name resolves to: the weaker providers laid down first and overwritten. */
    val byName: Map<String, Emote> = HashMap<String, Emote>(sevenTv.size + bttv.size + ffz.size).apply {
        putAll(sevenTv)
        putAll(bttv)
        putAll(ffz)
    }

    fun withSevenTv(emotes: Map<String, Emote>) = ProviderEmotes(ffz, bttv, emotes)

    /**
     * Takes over what a load brought back: one entry per provider that was asked, holding its
     * emotes or null if it did not answer.
     *
     * A provider that did not answer at all (null, as opposed to an empty list, which means it
     * has nothing here) keeps the emotes it already had: one provider being unreachable must not
     * turn its emotes into plain text, which is what the reload on every return to the app would
     * otherwise do. A provider that was not asked — because it answered last time and only the
     * silent ones are being tried again — keeps them for the same reason.
     */
    fun merge(results: Map<EmoteProvider, List<Emote>?>) = Merged(
        emotes = ProviderEmotes(
            ffz = results[EmoteProvider.Ffz]?.byName() ?: this.ffz,
            bttv = results[EmoteProvider.Bttv]?.byName() ?: this.bttv,
            sevenTv = results[EmoteProvider.SevenTv]?.byName() ?: this.sevenTv,
        ),
        failed = results.filterValues { it == null }.keys,
    )

    /** What a load leaves behind: the emotes to keep, and whoever was asked and did not answer. */
    class Merged(val emotes: ProviderEmotes, val failed: Set<EmoteProvider>)

    private fun List<Emote>.byName(): Map<String, Emote> = associateBy { it.name }
}
