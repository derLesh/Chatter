package me.lesh.chatter.emotes

import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.chat.AssetChanges
import me.lesh.chatter.chat.EmoteSource
import me.lesh.chatter.net.BttvEmote
import me.lesh.chatter.net.FfzEmote
import me.lesh.chatter.net.FfzRoomInfo
import me.lesh.chatter.net.ServiceTrouble
import me.lesh.chatter.net.SevenTvActiveEmote
import me.lesh.chatter.net.ThirdPartyEmoteApi
import me.lesh.chatter.net.TrustedImages
import me.lesh.chatter.net.TwitchEmoteApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * All emotes the app knows, keyed by the exact word that triggers them. Lookups are plain map gets,
 * so parsing a message is O(words).
 */
class EmoteRepository(
    private val helix: TwitchEmoteApi,
    private val thirdParty: ThirdPartyEmoteApi,
    private val trouble: ServiceTrouble = ServiceTrouble(),
    /** The providers the user has switched on; the others are never asked. */
    private val providersOn: () -> Set<EmoteProvider> = { EmoteProvider.entries.toSet() },
    /** The lists of the last start; none in tests. */
    private val files: EmoteListFiles? = null,
    /** Gets each channel's FFZ room as it arrives, for the mod and VIP pictures in it. */
    private val onFfzRoom: (channelId: String, room: FfzRoomInfo?) -> Unit = { _, _ -> },
    /** Injected for tests. */
    private val now: () -> Long = System::currentTimeMillis,
) : EmoteSource {
    @Volatile private var global: ProviderEmotes = ProviderEmotes()
    @Volatile private var twitchUser: Map<String, Emote> = emptyMap()
    private val channels = ConcurrentHashMap<String, ProviderEmotes>()
    private val channelLock = Any()
    private val channelLifetimes = HashMap<String, Any>()
    /** Twitch follower emotes the user may use, by channel id. */
    private val channelTwitch = ConcurrentHashMap<String, Map<String, Emote>>()

    /** Names and avatars of the channels the Twitch emotes belong to, by id; see [loadOwners]. */
    @Volatile private var ownerProfiles: Map<String, EmoteOwner> = emptyMap()

    /** Channels the user has sub emotes of, by id. */
    @Volatile private var subscribedChannels: Set<String> = emptySet()

    /** The channels the Twitch emotes belong to, by channel id, marked where the user is subscribed. */
    val twitchOwners: Map<String, EmoteOwner>
        get() = ownerProfiles.mapValues { (id, owner) -> if (id in subscribedChannels) owner.copy(subscribed = true) else owner }

    /** When every provider switched on last answered for a channel in one load; see [refreshChannel]. */
    private val lastFullLoad = ConcurrentHashMap<String, Long>()

    /** 7TV ids per Twitch channel id, for EventAPI live updates. */
    private val sevenTvSets = ConcurrentHashMap<String, String>()
    private val sevenTvUsers = ConcurrentHashMap<String, String>()

    /**
     * Per channel the number of the newest load that asked 7TV. Loads overlap, e.g. a full one still
     * waiting for BTTV while a set switch is fetched, and only the newest may set the 7TV emotes.
     */
    private val sevenTvLoads = ConcurrentHashMap<String, Long>()
    private val lastSevenTvLoad = AtomicLong()
    private val globalLock = Mutex()

    /**
     * Providers whose global emotes are missing: all of them before the first load, afterwards the
     * ones that did not answer or are switched off. [retryMissing] asks those switched on.
     */
    @Volatile private var globalMissing: Set<EmoteProvider> = THIRD_PARTY

    /**
     * False until global emotes were requested at all, so an app that has not started loading does
     * not count as waiting for a provider.
     */
    @Volatile private var globalAsked = false

    /** The same per channel. */
    private val channelMissing = ConcurrentHashMap<String, Set<EmoteProvider>>()

    /**
     * Channels whose first load has finished. Until then a channel owes every provider, so a message
     * built meanwhile stays rebuildable.
     */
    private val answered = ConcurrentHashMap.newKeySet<String>()

    /** Increments on every change, so the picker and autocomplete can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _waitingForProvider = MutableStateFlow(false)
    /** True while a provider still owes emotes. */
    val waitingForProvider: StateFlow<Boolean> = _waitingForProvider

    private val changes = AssetChanges()
    /** Latest arrivals by Twitch channel id; null means global emotes. */
    val arrived = changes.arrived

    override fun revision(channelId: String?): Long = changes.revision(channelId)

    /** Third-party emote for a word, channel before global. */
    override fun lookup(channelId: String?, word: String): Emote? =
        channelId?.let { channels[it]?.byName?.get(word) } ?: global.byName[word]

    /** Twitch emote the logged-in user owns, for rendering their own echoed messages. */
    override fun lookupOwnTwitch(channelId: String?, word: String): Emote? =
        twitchUser[word] ?: channelId?.let { channelTwitch[it]?.get(word) }
            ?: TwitchSmileys.typedBy(word)?.let { twitchUser[it] }

    /** All providers have answered for [channelId], so a message built now will not change. */
    override fun complete(channelId: String?): Boolean =
        globalMissing.thatAreOn().isEmpty() &&
            (channelId == null || (channelId in answered && channelMissing[channelId].orEmpty().thatAreOn().isEmpty()))

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

    /** The names the 7TV emote [emoteId] has in the emote set loaded for [channelId]; empty if it is not in it. */
    fun sevenTvNames(channelId: String, emoteId: String): List<String> =
        channels[channelId]?.sevenTv?.values?.filter { it.id == emoteId }?.map { it.name }.orEmpty()

    /** Every emote held in memory, counted once per channel it is loaded for. */
    fun loadedCount(): Int =
        global.byName.size + twitchUser.size +
            channels.values.sumOf { it.byName.size } + channelTwitch.values.sumOf { it.size }

    /** Asks the providers that still owe global emotes; no request once all are loaded. */
    suspend fun loadGlobal() {
        loadGlobal(globalMissing)
    }

    /** Asks [wanted] for their global emotes, those switched on. Returns the ones that did not answer. */
    private suspend fun loadGlobal(wanted: Set<EmoteProvider>): Set<EmoteProvider> = globalLock.withLock {
        globalAsked = true
        val asked = wanted.thatAreOn()
        if (asked.isEmpty()) return@withLock emptySet()
        if (global.byName.isEmpty()) restoreGlobal()
        val loaded = global.merge(ask(asked) { fetchGlobal(it) })
        val changed = global.byName != loaded.emotes.byName
        global = loaded.emotes
        if (changed) files?.write(EmoteListFiles.GLOBAL, loaded.emotes)
        AppLog.d(TAG, "Global emotes: ${loaded.emotes.counts()}${failedText(loaded.failed)}")
        if (changed) changes.changed()
        globalMissing = globalMissing - asked + loaded.failed
        updateWaiting()
        _version.update { it + 1 }
        loaded.failed
    }

    /** Until the providers answer, the global emotes of the last start. */
    private fun restoreGlobal() {
        val kept = files?.read(EmoteListFiles.GLOBAL) ?: return
        global = kept
        changes.changed()
        _version.update { it + 1 }
    }

    /**
     * Puts the channel's emotes of the last start in place until the providers answer, so its
     * messages get their emotes right away. True if there are any, kept or loaded.
     */
    fun restoreChannel(channelId: String): Boolean {
        if (channels.containsKey(channelId)) return true
        val kept = files?.read(channelId) ?: return false
        synchronized(channelLock) {
            channelLifetimes.getOrPut(channelId) { Any() }
            if (channels.putIfAbsent(channelId, kept) == null) {
                changes.changed(channelId)
                _version.update { it + 1 }
            }
        }
        return true
    }

    /**
     * Asks every provider switched on again, whatever was loaded: the global emotes, those of
     * [channelIds] and the Twitch emotes of [userId]. Returns the providers that did not answer.
     */
    suspend fun reload(channelIds: Collection<String>, userId: String?): Set<EmoteProvider> = coroutineScope {
        val twitch = async { if (userId != null) loadTwitchUserEmotes(userId) }
        val global = async { loadGlobal(THIRD_PARTY) }
        val channels = channelIds.map { id ->
            async {
                loadChannel(id, userId)
                channelMissing[id].orEmpty().thatAreOn()
            }
        }
        twitch.await()
        (global.await() + channels.awaitAll().flatten())
    }

    suspend fun loadTwitchUserEmotes(userId: String) {
        val list = safe("Twitch user emotes") { helix.userEmotes(userId) } ?: return
        twitchUser = list.associate {
            val owner = twitchOwnerId(it.ownerId)
            val name = TwitchSmileys.nameOf(it.name)
            name to Emote(name, it.id, twitchEmoteUrl(it.id), EmoteProvider.Twitch, isChannel = owner != null, ownerId = owner)
        }
        // Follower and bits emotes come from channels too, but only sub emotes mean a sub.
        subscribedChannels = list.filter { it.emoteType == "subscriptions" }.mapNotNullTo(HashSet()) { twitchOwnerId(it.ownerId) }
        AppLog.d(TAG, "Twitch emotes the account may use: ${twitchUser.size}, subs in ${subscribedChannels.size} channel(s)")
        _version.update { it + 1 }
        loadOwners(twitchUser.values.mapNotNullTo(HashSet()) { it.ownerId })
    }

    /** Asks Twitch for the channels in [ids] not known yet, for the picker's channel groups. */
    private suspend fun loadOwners(ids: Set<String>) {
        val missing = ids - ownerProfiles.keys
        if (missing.isEmpty()) return
        val users = safe("emote channels") { helix.usersById(missing.toList()) } ?: return
        ownerProfiles = ownerProfiles + users.associate { it.id to EmoteOwner(it.id, it.displayName.ifEmpty { it.login }, it.profileImageUrl.ifEmpty { null }) }
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
    ) = loadChannel(channelId, userId, wanted, expectedLifetime = null)

    private suspend fun loadChannel(
        channelId: String,
        userId: String?,
        wanted: Set<EmoteProvider>,
        expectedLifetime: Any?,
    ) = coroutineScope {
        if (expectedLifetime == null) restoreChannel(channelId)
        val asked = wanted.thatAreOn()
        var sevenTvLoad: Long? = null
        // Numbered with the lifetime check, so a load of a channel removed meanwhile cannot outrank
        // the load of its rejoin.
        val lifetime = synchronized(channelLock) {
            if (expectedLifetime != null && channelLifetimes[channelId] !== expectedLifetime) return@coroutineScope
            if (EmoteProvider.SevenTv in asked) sevenTvLoad = lastSevenTvLoad.incrementAndGet().also { sevenTvLoads[channelId] = it }
            channelLifetimes.getOrPut(channelId) { Any() }
        }
        val follower = async { if (userId != null) loadFollowerEmotes(channelId, userId, lifetime) }
        // Taken over with the emotes, so a load cancelled in between leaves both as they were.
        var sevenTvIds: SevenTvIds? = null
        val answers = ask(asked) { fetchChannel(channelId, it, lifetime) { ids -> sevenTvIds = ids } }
        // Merged into the current emotes once the answers are in, not into a snapshot from before
        // the request. Otherwise a 7TV change or another provider's retry finishing in between is
        // lost.
        follower.await()
        val toKeep = synchronized(channelLock) {
            if (channelLifetimes[channelId] !== lifetime) return@coroutineScope
            // A refresh replaced by a newer one is cancelled; it may have got here after its last await.
            ensureActive()
            // A newer load asked 7TV too; it decides 7TV's emotes and whether 7TV still owes them.
            val outdated = sevenTvLoad != null && sevenTvLoads[channelId] != sevenTvLoad
            val taken = if (outdated) answers - EmoteProvider.SevenTv else answers
            val takenFrom = if (outdated) asked - EmoteProvider.SevenTv else asked
            var changed = false
            var failed = emptySet<EmoteProvider>()
            val merged = channels.compute(channelId) { _, current ->
                (current ?: ProviderEmotes()).merge(taken).also {
                    failed = it.failed
                    changed = current?.byName != it.emotes.byName
                }.emotes
            }
            AppLog.d(TAG, "Emotes of ${AppLog.hidden("channel id $channelId", "a channel")}: ${merged?.counts()}${failedText(failed)}")
            if (changed) changes.changed(channelId)
            // Providers not asked this time keep their previous state; before the first load that is
            // missing, so one switched on later is loaded then.
            var owed = emptySet<EmoteProvider>()
            val missing = channelMissing.compute(channelId) { _, before ->
                owed = before ?: THIRD_PARTY
                owed - takenFrom + failed
            }.orEmpty()
            if (!outdated) sevenTvIds?.let { (set, user) ->
                if (set != null) sevenTvSets[channelId] = set else sevenTvSets.remove(channelId)
                if (user != null) sevenTvUsers[channelId] = user else sevenTvUsers.remove(channelId)
            }
            // A load of some providers does not make the others current.
            if (missing.thatAreOn().isEmpty() && takenFrom.containsAll(THIRD_PARTY.thatAreOn())) lastFullLoad[channelId] = now()
            answered.add(channelId)
            updateWaiting()
            _version.update { it + 1 }
            merged?.takeIf { changed }
        }
        // Outside the lock: a pushed 7TV change must not wait for the disk.
        toKeep?.let { files?.write(channelId, it) }
    }

    /**
     * Asks only the providers that did not answer, and those just switched on. Returns true if one
     * of them is back; [arrived] tells the chat too. The follower emotes are not asked again: they
     * were not what was missing.
     */
    suspend fun retryMissing(): Boolean {
        // A provider switched off since is no longer waited for.
        updateWaiting()
        var recovered = false
        if (globalAsked && globalMissing.thatAreOn().isNotEmpty()) {
            val before = globalMissing
            loadGlobal()
            if (globalMissing != before) recovered = true
        }
        // Over a copy: loading writes into this map.
        val pending = synchronized(channelLock) {
            channelMissing.mapNotNull { (id, missing) -> channelLifetimes[id]?.let { Triple(id, missing, it) } }
        }
        pending.forEach { (channelId, missing, lifetime) ->
            if (missing.thatAreOn().isEmpty()) return@forEach
            loadChannel(channelId, userId = null, wanted = missing, expectedLifetime = lifetime)
            if (channelMissing[channelId].orEmpty() != missing) recovered = true
        }
        return recovered
    }

    /**
     * Updates a channel's emotes after the app was away, unless they were fully loaded recently.
     * Emote sets rarely change and 7TV pushes its changes anyway, so asking every provider of every
     * channel on each return would be a lot of requests for nothing. A failed load is retried at
     * once.
     */
    suspend fun refreshChannel(channelId: String) {
        val lifetime = synchronized(channelLock) { channelLifetimes[channelId] } ?: return
        val last = lastFullLoad[channelId]
        val owed = channelMissing[channelId].orEmpty().thatAreOn().isNotEmpty()
        if (!owed && last != null && now() - last < RELOAD_AFTER_MS) return
        loadChannel(channelId, null, THIRD_PARTY, expectedLifetime = lifetime)
    }

    /**
     * Asks 7TV alone for a channel's emote set, whenever it was loaded: after the EventAPI was
     * away, or when the channel switched sets. The other providers are not affected by either.
     */
    suspend fun refreshSevenTv(channelId: String) {
        if (EmoteProvider.SevenTv !in providersOn()) return
        val lifetime = synchronized(channelLock) { channelLifetimes[channelId] } ?: return
        loadChannel(channelId, null, setOf(EmoteProvider.SevenTv), expectedLifetime = lifetime)
    }

    private suspend fun loadFollowerEmotes(channelId: String, userId: String, lifetime: Any) {
        val emotes = safe("Twitch channel emotes") { helix.channelEmotes(channelId) }
            ?.filter { it.emoteType == "follower" }
            ?.takeIf { it.isNotEmpty() } ?: return
        // The broadcaster can always use their own emotes.
        val usable = userId == channelId || safe("follow status") { helix.isFollowing(userId, channelId) } == true
        if (usable) {
            synchronized(channelLock) {
                if (channelLifetimes[channelId] !== lifetime) return
                channelTwitch[channelId] = emotes.associate { it.name to Emote(it.name, it.id, twitchEmoteUrl(it.id), EmoteProvider.Twitch, isChannel = true, ownerId = channelId) }
            }
            loadOwners(setOf(channelId))
        }
    }

    /** EventAPI subscriptions: set changes and set switches of every channel. */
    fun sevenTvSubscriptions(): Set<SevenTvSubscription> {
        if (EmoteProvider.SevenTv !in providersOn()) return emptySet()
        return sevenTvSets.values.mapTo(HashSet()) { SevenTvSubscription.ofObject("emote_set.update", it) } +
            sevenTvUsers.values.map { SevenTvSubscription.ofObject("user.update", it) }
    }

    fun channelsForSevenTvSet(setId: String): List<String> = sevenTvSets.entries.filter { it.value == setId }.map { it.key }
    fun channelsForSevenTvUser(userId: String): List<String> = sevenTvUsers.entries.filter { it.value == userId }.map { it.key }

    /** Applies a pushed 7TV set change. Returns the added emotes. */
    fun applySevenTvUpdate(channelId: String, event: SevenTvEvent.EmoteSetUpdate): List<Emote> = synchronized(channelLock) {
        // The set may have been switched since the event was routed here.
        if (channelId !in channelLifetimes || sevenTvSets[channelId] != event.setId) return emptyList()
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

    /** Explicit removal also invalidates requests still on their way back. */
    fun removeChannel(channelId: String) = synchronized(channelLock) {
        files?.delete(channelId)
        channelLifetimes.remove(channelId)
        channels.remove(channelId)
        channelTwitch.remove(channelId)
        channelMissing.remove(channelId)
        changes.forget(channelId)
        answered.remove(channelId)
        lastFullLoad.remove(channelId)
        sevenTvSets.remove(channelId)
        sevenTvUsers.remove(channelId)
        sevenTvLoads.remove(channelId)
        updateWaiting()
        _version.update { it + 1 }
    }

    fun clear() = synchronized(channelLock) {
        channelLifetimes.clear()
        channels.clear()
        channelMissing.clear()
        answered.clear()
        updateWaiting()
        lastFullLoad.clear()
        sevenTvSets.clear()
        sevenTvUsers.clear()
        sevenTvLoads.clear()
        channelTwitch.clear()
        twitchUser = emptyMap()
        ownerProfiles = emptyMap()
        subscribedChannels = emptySet()
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

    private suspend fun fetchChannel(
        channelId: String,
        provider: EmoteProvider,
        lifetime: Any,
        onSevenTvIds: (SevenTvIds) -> Unit,
    ): List<Emote>? = when (provider) {
        EmoteProvider.Ffz -> answer(provider) {
            val room = thirdParty.ffzChannel(channelId)
            if (synchronized(channelLock) { channelLifetimes[channelId] === lifetime }) onFfzRoom(channelId, room?.room)
            room?.sets?.values?.flatMap { it.emoticons }?.mapNotNull { it.toEmote(true) }.orEmpty()
        }
        EmoteProvider.Bttv -> answer(provider) {
            thirdParty.bttvChannel(channelId)?.let { it.channelEmotes + it.sharedEmotes }?.map { it.toEmote(true) }.orEmpty()
        }
        EmoteProvider.SevenTv -> answer(provider) {
            val user = thirdParty.sevenTvChannel(channelId)
            onSevenTvIds(SevenTvIds(user?.emoteSet?.id?.takeIf { it.isNotEmpty() }, user?.user?.id?.takeIf { it.isNotEmpty() }))
            user?.emoteSet?.emotes.orEmpty().mapNotNull { it.toEmote(true) }
        }
        EmoteProvider.Twitch -> null
    }

    /** One provider's answer, or null. [ServiceTrouble] reports the outage once per run. */
    private suspend fun <T> answer(provider: EmoteProvider, block: suspend () -> T): T? = try {
        block().also { trouble.reachable(provider.label) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w(TAG, "Loading ${provider.label} failed: ${e.message}")
        trouble.report(provider.label)
        null
    }

    private fun updateWaiting() {
        _waitingForProvider.value = (globalAsked && globalMissing.thatAreOn().isNotEmpty()) ||
            channelMissing.values.any { it.thatAreOn().isNotEmpty() }
    }

    private fun Set<EmoteProvider>.thatAreOn(): Set<EmoteProvider> = this intersect providersOn()

    private suspend fun <T> safe(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w(TAG, "Loading $what failed: ${e.message}")
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
            modifier = if (modifier) modifierFlags else 0,
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

/** A channel's active 7TV emote set and its 7TV account, as the EventAPI is subscribed to them. */
private data class SevenTvIds(val set: String?, val user: String?)

private fun ProviderEmotes.counts() = "7TV ${sevenTv.size}, BTTV ${bttv.size}, FFZ ${ffz.size}"

private fun failedText(failed: Set<EmoteProvider>) =
    if (failed.isEmpty()) "" else ", no answer from ${failed.joinToString { it.label }}"

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
