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
import me.lesh.chatter.net.SevenTvAddResult
import me.lesh.chatter.net.SevenTvApi
import me.lesh.chatter.net.SevenTvChannel
import me.lesh.chatter.net.SevenTvLoginStart
import java.util.concurrent.ConcurrentHashMap

/** What the button on a 7TV emote's card shows. */
enum class EmoteButton { Hidden, Add, Added }

/** How tapping that button went. */
enum class EmoteAddOutcome { Added, NameTaken, NeedsLogin, Failed }

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
 * Adding 7TV emotes to the active emote set of the channel they were linked in. The button only
 * shows for the streamer and the channel's editors, which 7TV tells anyone, so nothing about the
 * user is sent to find out. Adding needs a login with 7TV, done once per Twitch account and kept
 * on the phone.
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
    /** Whether the channel already has the emote, from the emotes loaded for it. */
    private val hasEmote: (channelId: String, emoteId: String) -> Boolean,
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
     * Emotes added from this phone, by channel id, then emote id, with the
     * [SystemClock.elapsedRealtime] until which they count as there. 7TV's live update takes a
     * moment; after that the loaded emotes say whether it is still there.
     */
    private val added = ConcurrentHashMap<String, Map<String, Long>>()

    /** [sourceRoomId]: the Shared Chat partner a message was written in, whose emotes it is about. */
    suspend fun button(channel: String, sourceRoomId: String?, emoteId: String): EmoteButton {
        val twitchUserId = activeUserId.value ?: return EmoteButton.Hidden
        val roomId = sourceRoomId ?: channelId(channel) ?: return EmoteButton.Hidden
        return try {
            val owner = channelAccount(roomId) ?: return EmoteButton.Hidden
            if (owner.style?.activeEmoteSetId.isNullOrEmpty()) return EmoteButton.Hidden
            val sevenTvUserId = logins.value.firstOrNull { it.twitchUserId == twitchUserId }?.sevenTvUserId
            when {
                !mayEditEmotes(owner, roomId, twitchUserId, sevenTvUserId) -> EmoteButton.Hidden
                hasEmote(roomId, emoteId) || justAdded(roomId, emoteId) -> EmoteButton.Added
                else -> EmoteButton.Add
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not tell whether emotes can be added: ${e.message}")
            EmoteButton.Hidden
        }
    }

    /**
     * Adds the emote [emoteId] under [name] to the active emote set of [channel], or of
     * [sourceRoomId] for a Shared Chat message. [account]: the Twitch account the tap was for; if
     * another one is active by now, nothing is added.
     */
    suspend fun add(channel: String, sourceRoomId: String?, emoteId: String, name: String, account: String?): EmoteAddOutcome {
        val twitchUserId = activeUserId.value?.takeIf { account == null || it == account } ?: return EmoteAddOutcome.Failed
        val login = logins.value.firstOrNull { it.twitchUserId == twitchUserId } ?: return EmoteAddOutcome.NeedsLogin
        val roomId = sourceRoomId ?: channelId(channel) ?: return EmoteAddOutcome.Failed
        return try {
            val token = when (val opened = withContext(Dispatchers.IO) { TokenCipher.open(login.token) }) {
                is Decrypted.Plain -> opened.text
                // The keystore usually answers again within seconds; the login is still good.
                Decrypted.Unavailable -> return EmoteAddOutcome.Failed
                Decrypted.Lost -> {
                    forget(twitchUserId)
                    return EmoteAddOutcome.NeedsLogin
                }
            }
            // Asked again rather than taken from the cache: the streamer may have switched sets since.
            val setId = refreshChannel(roomId)?.style?.activeEmoteSetId?.takeIf { it.isNotEmpty() } ?: return EmoteAddOutcome.Failed
            when (val result = api.addEmote(token, setId, emoteId, name)) {
                SevenTvAddResult.Added -> {
                    AppLog.i(TAG, "Added a 7TV emote in ${AppLog.channel(channel)}")
                    val until = SystemClock.elapsedRealtime() + ADDED_MS
                    added.merge(roomId, mapOf(emoteId to until)) { old, new -> old.filterValues { it > SystemClock.elapsedRealtime() } + new }
                    EmoteAddOutcome.Added
                }
                SevenTvAddResult.NameTaken -> EmoteAddOutcome.NameTaken
                SevenTvAddResult.LoggedOut -> {
                    AppLog.i(TAG, "7TV no longer accepts the login")
                    forget(twitchUserId)
                    EmoteAddOutcome.NeedsLogin
                }
                is SevenTvAddResult.Refused -> {
                    AppLog.w(TAG, "7TV refused to add an emote: ${result.message}")
                    EmoteAddOutcome.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not add a 7TV emote: ${e.message}")
            EmoteAddOutcome.Failed
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

    private fun justAdded(roomId: String, emoteId: String): Boolean =
        (added[roomId]?.get(emoteId) ?: 0L) > SystemClock.elapsedRealtime()

    private fun decode(text: String?): List<StoredSevenTvLogin> =
        if (text.isNullOrEmpty()) emptyList() else runCatching { AppJson.decodeFromString(SERIALIZER, text) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "SevenTvEditing"
        val LOGINS_KEY = stringPreferencesKey("seventv_logins")
        val SERIALIZER = ListSerializer(StoredSevenTvLogin.serializer())

        /** Editors change rarely; a removed editor learns of it from 7TV's answer anyway. */
        const val FRESH_MS = 5 * 60 * 1000L

        /** Long enough for 7TV's live update to arrive, short enough that a removal shows soon after. */
        const val ADDED_MS = 30 * 1000L
    }
}
