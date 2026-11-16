package me.lesh.chatter.emotes

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.lesh.chatter.auth.Decrypted
import me.lesh.chatter.auth.TokenCipher
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.SevenTvEditResult
import me.lesh.chatter.net.SevenTvApi
import me.lesh.chatter.net.SevenTvChannel
import me.lesh.chatter.net.SevenTvLoginStart
import java.util.concurrent.ConcurrentHashMap

/** What the button on a 7TV emote's card shows. */
enum class EmoteButton { Hidden, Add, Added }

/**
 * How adding, renaming or removing a 7TV emote went. [SetSwitched]: the channel switched its
 * active set after the emote card was opened, so nothing was changed.
 */
enum class EmoteEditOutcome { Done, NameTaken, NeedsLogin, SetSwitched, Failed }

/**
 * What the emote card may offer for a 7TV emote: the channel's active set when the card asked, and
 * the [name] the emote has there, or null if that set does not have it.
 */
data class EmoteAccess(val activeSetId: String, val name: String?)

/** One of a channel's 7TV emote sets, for the emote card: whether it is the [active] one and has the emote. */
data class EmoteSetChoice(val id: String, val name: String, val active: Boolean, val hasEmote: Boolean)

/**
 * A 7TV login, stored per Twitch account. [token] is encrypted with [TokenCipher].
 * [sevenTvUserId] is the 7TV account it logs in, which need not be the one linked to the Twitch account.
 */
@Serializable
data class StoredSevenTvLogin(val twitchUserId: String, val sevenTvUserId: String, val token: String)

/**
 * Whether the Twitch user [twitchUserId] may add emotes to [channel], the 7TV account of the Twitch
 * channel [channelId]: as the streamer, or as an editor who may change its emote sets. An editor is
 * recognized by the Twitch account linked to their 7TV account, or by [sevenTvUserId], the 7TV
 * account they logged in with, if any.
 */
fun mayEditEmotes(channel: SevenTvChannel, channelId: String, twitchUserId: String, sevenTvUserId: String?): Boolean {
    if (twitchUserId == channelId || (sevenTvUserId != null && sevenTvUserId == channel.id)) return true
    return channel.editors.any { editor ->
        editor.state == ACCEPTED && editor.permissions.emoteSet.manage &&
            ((sevenTvUserId != null && editor.editorId == sevenTvUserId) ||
                editor.editor?.connections.orEmpty().any { it.platform == TWITCH && it.platformId == twitchUserId })
    }
}

private const val ACCEPTED = "ACCEPTED"
private const val TWITCH = "TWITCH"

/**
 * Changing a channel's 7TV emotes: adding linked emotes to its active set, and renaming, removing
 * and adding emotes from the emote card. This only shows for the streamer and the channel's
 * editors, which 7TV tells anyone, so nothing about the user is sent to find out. Changing needs a
 * login with 7TV, done once per Twitch account and kept on the phone.
 *
 * What 7TV says about a channel is kept for a few minutes in memory, so cards of the same channel
 * ask once.
 */
class SevenTvEditing(
    private val store: DataStore<Preferences>,
    private val api: SevenTvApi,
    /** The active Twitch account; null for a guest. */
    private val activeUserId: StateFlow<String?>,
    /** The Twitch id of a joined channel. */
    private val channelId: (channel: String) -> String?,
    /** The names an emote has in a channel's active set, from the emotes loaded for it; empty if it is not there. */
    private val loadedNames: (channelId: String, emoteId: String) -> List<String>,
    private val scope: CoroutineScope,
) {
    private val logins: StateFlow<List<StoredSevenTvLogin>> = store.data
        .map { it[LOGINS_KEY] }
        .distinctUntilChanged()
        .map(::decode)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Whether the active account is logged in to 7TV. */
    val loggedIn: StateFlow<Boolean> = combine(logins, activeUserId) { all, me -> me != null && all.any { it.twitchUserId == me } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private class Known<T>(val value: T, private val until: Long) {
        val fresh get() = SystemClock.elapsedRealtime() < until
    }

    /** By Twitch channel id; null for a channel without a 7TV account. */
    private val channels = ConcurrentHashMap<String, Known<SevenTvChannel?>>()

    /**
     * The names emotes have in a channel's active set after a change from this phone, by channel id,
     * then emote id. 7TV's live update takes a moment; until it arrives these count, after that the
     * loaded emotes do, including any change someone else made since.
     */
    private val recent = ConcurrentHashMap<String, Map<String, RecentChange>>()

    /** [names] is empty once the emote was removed; [loadedBefore] is what the loaded emotes said at the change. */
    private class RecentChange(val names: List<String>, val loadedBefore: List<String>, val until: Long)

    /** [sourceRoomId]: the Shared Chat partner a message was written in, whose emotes it is about. */
    suspend fun button(channel: String, sourceRoomId: String?, emoteId: String): EmoteButton {
        val roomId = sourceRoomId ?: channelId(channel) ?: return EmoteButton.Hidden
        return when {
            editableSet(roomId) == null -> EmoteButton.Hidden
            currentNames(roomId, emoteId).isNotEmpty() -> EmoteButton.Added
            else -> EmoteButton.Add
        }
    }

    /**
     * What the emote card may offer for the 7TV emote [emoteId], shown as [shownName] in [channel]'s
     * chat; null if the user may not change the channel's emotes. The message may be older than a
     * rename, so the name comes from the set as it is now. [fresh] asks 7TV again rather than
     * trusting the cache, for a card just opened: the channel may have switched sets since.
     */
    suspend fun access(channel: String, emoteId: String, shownName: String, fresh: Boolean): EmoteAccess? {
        val roomId = channelId(channel) ?: return null
        val activeSet = editableSet(roomId, fresh) ?: return null
        val names = currentNames(roomId, emoteId)
        return EmoteAccess(activeSet, names.firstOrNull { it == shownName } ?: names.firstOrNull())
    }

    /**
     * The active set of the channel [roomId], if the user may change its emotes. [fresh] asks 7TV
     * again, but only when the cache had an answer and it says the user may edit: most people never
     * can, and an answer fetched just now is fresh already.
     */
    private suspend fun editableSet(roomId: String, fresh: Boolean = false): String? {
        val twitchUserId = activeUserId.value ?: return null
        return try {
            val wasCached = channels[roomId]?.fresh == true
            val cached = channelAccount(roomId) ?: return null
            val ask = fresh && wasCached && mayEdit(cached, roomId, twitchUserId)
            val owner = (if (ask) refreshChannel(roomId) else cached) ?: return null
            val activeSet = owner.style?.activeEmoteSetId?.takeIf { it.isNotEmpty() } ?: return null
            activeSet.takeIf { mayEdit(owner, roomId, twitchUserId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not tell whether emotes can be changed: ${e.message}")
            null
        }
    }

    /**
     * The emote sets [channel] switches between, the active one first, and whether each has the
     * emote [emoteId]. [name] is what it is called in the active set, which 7TV searches by, so a
     * set holding it under another name shows it missing. Null if 7TV could not be asked.
     */
    suspend fun sets(channel: String, emoteId: String, name: String): List<EmoteSetChoice>? {
        val roomId = channelId(channel) ?: return null
        return try {
            val active = refreshChannel(roomId)?.style?.activeEmoteSetId ?: return null
            api.ownedSets(roomId, name).orEmpty()
                .filter { it.kind == NORMAL }
                .map { set -> EmoteSetChoice(set.id, set.name, set.id == active, set.emotes.items.any { it.id == emoteId }) }
                .sortedByDescending { it.active }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not load the 7TV emote sets: ${e.message}")
            null
        }
    }

    /**
     * Adds the emote [emoteId] under [name] to the set [setId] of [channel], or to its active set for
     * null, or to the active set of [sourceRoomId] for a Shared Chat message. [wasActive]: [setId]
     * was picked as the active set, so it must still be. [account]: the Twitch account the tap was
     * for; if another one is active by now, nothing is changed. The same goes for [rename] and [remove].
     */
    suspend fun add(
        channel: String,
        sourceRoomId: String?,
        setId: String?,
        wasActive: Boolean,
        emoteId: String,
        name: String,
        account: String?,
    ): EmoteEditOutcome =
        edit(channel, sourceRoomId, setId, mustBeActive = wasActive, account, "add") { token, roomId, set, active ->
            api.addEmote(token, set, emoteId, name).also {
                if (it == SevenTvEditResult.Done && active) noteChange(roomId, emoteId, currentNames(roomId, emoteId) + name)
            }
        }

    /**
     * Renames the emote [emoteId] from [name] to [newName] in [setId], the channel's active set when
     * the card asked; if the channel switched sets since, nothing is renamed. The same goes for [remove].
     */
    suspend fun rename(channel: String, setId: String, emoteId: String, name: String, newName: String, account: String?): EmoteEditOutcome =
        edit(channel, null, setId, mustBeActive = true, account, "rename") { token, roomId, set, _ ->
            api.renameEmote(token, set, emoteId, name, newName).also {
                if (it == SevenTvEditResult.Done) noteChange(roomId, emoteId, currentNames(roomId, emoteId) - name + newName)
            }
        }

    /** Takes the emote [emoteId], called [name] there, out of [setId], the channel's active set when the card asked. */
    suspend fun remove(channel: String, setId: String, emoteId: String, name: String, account: String?): EmoteEditOutcome =
        edit(channel, null, setId, mustBeActive = true, account, "remove") { token, roomId, set, _ ->
            api.removeEmote(token, set, emoteId, name).also {
                if (it == SevenTvEditResult.Done) noteChange(roomId, emoteId, currentNames(roomId, emoteId) - name)
            }
        }

    /**
     * Runs [change] with the login's token on the set [setId] of the channel, or on its active set
     * for null. [mustBeActive]: [setId] was the active set when the user chose, and must still be.
     * [change] gets the channel's id, the set and whether that set is the active one.
     */
    private suspend fun edit(
        channel: String,
        sourceRoomId: String?,
        setId: String?,
        mustBeActive: Boolean,
        account: String?,
        what: String,
        change: suspend (token: String, roomId: String, setId: String, active: Boolean) -> SevenTvEditResult,
    ): EmoteEditOutcome {
        val twitchUserId = activeUserId.value?.takeIf { account == null || it == account } ?: return EmoteEditOutcome.Failed
        val login = logins.value.firstOrNull { it.twitchUserId == twitchUserId } ?: return EmoteEditOutcome.NeedsLogin
        val roomId = sourceRoomId ?: channelId(channel) ?: return EmoteEditOutcome.Failed
        return try {
            val token = when (val opened = withContext(Dispatchers.IO) { TokenCipher.open(login.token) }) {
                is Decrypted.Plain -> opened.text
                // The keystore usually answers again within seconds; the login is still good.
                Decrypted.Unavailable -> return EmoteEditOutcome.Failed
                Decrypted.Lost -> {
                    forget(twitchUserId)
                    return EmoteEditOutcome.NeedsLogin
                }
            }
            // Asked again rather than taken from the cache: the streamer may have switched sets since.
            val active = refreshChannel(roomId)?.style?.activeEmoteSetId?.takeIf { it.isNotEmpty() }
            val set = setId ?: active ?: return EmoteEditOutcome.Failed
            if (mustBeActive && set != active) return EmoteEditOutcome.SetSwitched
            when (val result = change(token, roomId, set, set == active)) {
                SevenTvEditResult.Done -> {
                    AppLog.i(TAG, "7TV emote $what done in ${AppLog.channel(channel)}")
                    EmoteEditOutcome.Done
                }
                SevenTvEditResult.NameTaken -> EmoteEditOutcome.NameTaken
                SevenTvEditResult.LoggedOut -> {
                    AppLog.i(TAG, "7TV no longer accepts the login")
                    forget(twitchUserId)
                    EmoteEditOutcome.NeedsLogin
                }
                is SevenTvEditResult.Refused -> {
                    AppLog.w(TAG, "7TV refused to $what an emote: ${result.message}")
                    EmoteEditOutcome.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not $what a 7TV emote: ${e.message}")
            EmoteEditOutcome.Failed
        }
    }

    suspend fun startLogin(): SevenTvLoginStart = api.startLogin()

    /**
     * Finishes the login [start] began for the Twitch account [account] with the [code] from 7TV's
     * callback. False if it failed, or if [account] is no longer the active one: it may have been
     * removed meanwhile, and its login must not come back.
     */
    suspend fun finishLogin(start: SevenTvLoginStart, code: String, account: String): Boolean {
        if (activeUserId.value != account) return false
        return try {
            val token = api.finishLogin(start, code)
            val sevenTvUserId = api.me(token) ?: return false
            val encrypted = withContext(Dispatchers.IO) { TokenCipher.encrypt(token) }
            if (activeUserId.value != account) return false
            update { all -> all.filter { it.twitchUserId != account } + StoredSevenTvLogin(account, sevenTvUserId, encrypted) }
            AppLog.i(TAG, "Logged in to 7TV")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network, an unreadable answer, or the keystore refusing to encrypt.
            AppLog.w(TAG, "Could not log in to 7TV: ${e.message}")
            false
        }
    }

    /** Logs the active account out of 7TV, at 7TV too where it can be reached. */
    fun logout() {
        val twitchUserId = activeUserId.value ?: return
        val login = logins.value.firstOrNull { it.twitchUserId == twitchUserId } ?: return
        scope.launch {
            forget(twitchUserId)
            AppLog.i(TAG, "Logged out of 7TV")
            try {
                withContext(Dispatchers.IO) { TokenCipher.decrypt(login.token) }?.let { api.logout(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Could not end the session at 7TV: ${e.message}")
            }
        }
    }

    /** Drops the logins of Twitch accounts no longer on the phone. */
    suspend fun keepOnly(twitchUserIds: Collection<String>) {
        if (logins.value.all { it.twitchUserId in twitchUserIds }) return
        update { all -> all.filter { it.twitchUserId in twitchUserIds } }
    }

    private suspend fun forget(twitchUserId: String) = update { all -> all.filter { it.twitchUserId != twitchUserId } }

    private suspend fun update(change: (List<StoredSevenTvLogin>) -> List<StoredSevenTvLogin>) {
        store.edit { p ->
            val next = change(decode(p[LOGINS_KEY]))
            if (next.isEmpty()) p.remove(LOGINS_KEY) else p[LOGINS_KEY] = AppJson.encodeToString(SERIALIZER, next)
        }
    }

    private suspend fun channelAccount(roomId: String): SevenTvChannel? {
        channels[roomId]?.takeIf { it.fresh }?.let { return it.value }
        return refreshChannel(roomId)
    }

    private suspend fun refreshChannel(roomId: String): SevenTvChannel? {
        val channel = api.channel(roomId)
        channels[roomId] = Known(channel, SystemClock.elapsedRealtime() + FRESH_MS)
        return channel
    }

    private fun mayEdit(owner: SevenTvChannel, roomId: String, twitchUserId: String): Boolean {
        val sevenTvUserId = logins.value.firstOrNull { it.twitchUserId == twitchUserId }?.sevenTvUserId
        return mayEditEmotes(owner, roomId, twitchUserId, sevenTvUserId)
    }

    private fun noteChange(roomId: String, emoteId: String, names: List<String>) {
        val change = RecentChange(names.distinct(), loadedNames(roomId, emoteId), SystemClock.elapsedRealtime() + RECENT_MS)
        recent.merge(roomId, mapOf(emoteId to change)) { old, new -> old.filterValues { it.until > SystemClock.elapsedRealtime() } + new }
    }

    /**
     * The names the emote has in the active set: after a recent change from this phone, until the
     * loaded emotes change, else as loaded.
     */
    private fun currentNames(roomId: String, emoteId: String): List<String> {
        val loaded = loadedNames(roomId, emoteId)
        val change = recent[roomId]?.get(emoteId) ?: return loaded
        if (change.until > SystemClock.elapsedRealtime() && change.loadedBefore == loaded) return change.names
        // Dropped for good: a later rename back to the old name must not bring it back.
        recent.computeIfPresent(roomId) { _, changes -> (changes - emoteId).ifEmpty { null } }
        return loaded
    }

    private fun decode(text: String?): List<StoredSevenTvLogin> =
        if (text.isNullOrEmpty()) emptyList() else runCatching { AppJson.decodeFromString(SERIALIZER, text) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "SevenTvEditing"
        val LOGINS_KEY = stringPreferencesKey("seventv_logins")
        val SERIALIZER = ListSerializer(StoredSevenTvLogin.serializer())

        /** Editors change rarely; a removed editor learns of it from 7TV's answer anyway. */
        const val FRESH_MS = 5 * 60 * 1000L

        /** Long enough for 7TV's live update to arrive, short enough that a change from elsewhere shows soon after. */
        const val RECENT_MS = 30 * 1000L

        /** The kind of set a channel switches between; personal and special sets are 7TV's own. */
        const val NORMAL = "NORMAL"
    }
}
