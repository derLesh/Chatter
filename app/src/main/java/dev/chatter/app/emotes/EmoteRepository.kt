package dev.chatter.app.emotes

import android.util.Log
import dev.chatter.app.chat.EmoteSource
import dev.chatter.app.net.BttvEmote
import dev.chatter.app.net.FfzEmote
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.SevenTvActiveEmote
import dev.chatter.app.net.ThirdPartyApi
import kotlinx.coroutines.async
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
 * A load that did not answer, so the chat can say which emotes are missing instead of quietly
 * showing their names as text. [channelId] is null for the global emotes.
 */
data class EmoteLoadFailure(val channelId: String?, val providers: List<EmoteProvider>)

/**
 * Holds every emote known to the app, keyed by the exact word that triggers it.
 * Lookups are plain HashMap gets so parsing a message stays O(words).
 */
class EmoteRepository(
    private val helix: HelixApi,
    private val thirdParty: ThirdPartyApi,
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
    private var globalLoaded = false

    /** Increments whenever emote data changes, so UI lists (picker, autocomplete) can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Loads that came back empty because the provider was unreachable, for the chat to report. */
    private val _failures = MutableSharedFlow<EmoteLoadFailure>(extraBufferCapacity = 16)
    val failures: SharedFlow<EmoteLoadFailure> = _failures

    /** Third-party emote (channel first, then global) for a word in someone's message. */
    override fun lookup(channelId: String?, word: String): Emote? =
        channelId?.let { channels[it]?.byName?.get(word) } ?: global.byName[word]

    /** Twitch emote the logged-in user owns; used to render our own (locally echoed) messages. */
    override fun lookupOwnTwitch(channelId: String?, word: String): Emote? =
        twitchUser[word] ?: channelId?.let { channelTwitch[it]?.get(word) }

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

    suspend fun loadGlobal() = globalLock.withLock {
        if (globalLoaded) return@withLock
        coroutineScope {
            val ffz = async { safe("FFZ global") { thirdParty.ffzGlobal().let { g -> g.defaultSets.flatMap { g.sets[it.toString()]?.emoticons.orEmpty() } }.map { it.toEmote(false) } } }
            val bttv = async { safe("BTTV global") { thirdParty.bttvGlobal().map { it.toEmote(false) } } }
            val stv = async { safe("7TV global") { thirdParty.sevenTvGlobal().emotes.orEmpty().mapNotNull { it.toEmote(false) } } }
            val loaded = global.merge(ffz.await(), bttv.await(), stv.await())
            global = loaded.emotes
            globalLoaded = loaded.failed.isEmpty()
            if (loaded.failed.isNotEmpty()) _failures.tryEmit(EmoteLoadFailure(null, loaded.failed))
        }
        _version.update { it + 1 }
    }

    suspend fun loadTwitchUserEmotes(userId: String) {
        val list = safe("Twitch user emotes") { helix.userEmotes(userId) } ?: return
        twitchUser = list.associate { it.name to Emote(it.name, it.id, twitchEmoteUrl(it.id), EmoteProvider.Twitch, isChannel = it.emoteType != "globals" && it.emoteType != "smilies") }
        _version.update { it + 1 }
    }

    /** Third-party channel emotes plus the channel's Twitch follower emotes (if [userId] follows). */
    suspend fun loadChannel(channelId: String, userId: String?) = coroutineScope {
        val follower = async { if (userId != null) loadFollowerEmotes(channelId, userId) }
        val ffz = async { safe("FFZ channel") { thirdParty.ffzChannel(channelId)?.sets?.values?.flatMap { it.emoticons }?.map { it.toEmote(true) }.orEmpty() } }
        val bttv = async { safe("BTTV channel") { thirdParty.bttvChannel(channelId)?.let { it.channelEmotes + it.sharedEmotes }?.map { it.toEmote(true) }.orEmpty() } }
        val stv = async {
            safe("7TV channel") {
                val user = thirdParty.sevenTvChannel(channelId)
                user?.emoteSet?.id?.takeIf { it.isNotEmpty() }?.let { sevenTvSets[channelId] = it } ?: sevenTvSets.remove(channelId)
                user?.user?.id?.takeIf { it.isNotEmpty() }?.let { sevenTvUsers[channelId] = it } ?: sevenTvUsers.remove(channelId)
                user?.emoteSet?.emotes.orEmpty().mapNotNull { it.toEmote(true) }
            }
        }
        val loaded = (channels[channelId] ?: ProviderEmotes()).merge(ffz.await(), bttv.await(), stv.await())
        channels[channelId] = loaded.emotes
        if (loaded.failed.isEmpty()) lastFullLoad[channelId] = System.currentTimeMillis() else lastFullLoad.remove(channelId)
        if (loaded.failed.isNotEmpty()) _failures.tryEmit(EmoteLoadFailure(channelId, loaded.failed))
        follower.await()
        _version.update { it + 1 }
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
        if (last != null && System.currentTimeMillis() - last < RELOAD_AFTER_MS) return
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
        lastFullLoad.clear()
        sevenTvSets.clear()
        sevenTvUsers.clear()
        channelTwitch.clear()
        twitchUser = emptyMap()
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
     * Takes over what a load brought back. A provider that did not answer at all (null, as
     * opposed to an empty list, which means it has nothing here) keeps the emotes it already had:
     * one provider being unreachable must not turn its emotes into plain text, which is what the
     * reload on every return to the app would otherwise do.
     */
    fun merge(ffz: List<Emote>?, bttv: List<Emote>?, sevenTv: List<Emote>?) = Merged(
        emotes = ProviderEmotes(
            ffz = ffz?.byName() ?: this.ffz,
            bttv = bttv?.byName() ?: this.bttv,
            sevenTv = sevenTv?.byName() ?: this.sevenTv,
        ),
        failed = listOfNotNull(
            EmoteProvider.Ffz.takeIf { ffz == null },
            EmoteProvider.Bttv.takeIf { bttv == null },
            EmoteProvider.SevenTv.takeIf { sevenTv == null },
        ),
    )

    /** What a load leaves behind: the emotes to keep, and whoever did not answer. */
    class Merged(val emotes: ProviderEmotes, val failed: List<EmoteProvider>)

    private fun List<Emote>.byName(): Map<String, Emote> = associateBy { it.name }
}
