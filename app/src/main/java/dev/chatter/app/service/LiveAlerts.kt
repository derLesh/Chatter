package dev.chatter.app.service

import android.util.Log
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.auth.TwitchScopes
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.channels.LiveAlertChoices
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixStream
import dev.chatter.app.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks Twitch what is live, for the tabs while the app is on screen and for live notifications:
 * by default the channels in the list, and any other followed channel the user turned on. Also in
 * the background, since the moment a stream starts is the moment somebody wants its chat.
 */
class LiveAlerts(
    private val helix: HelixApi,
    private val auth: AuthRepository,
    private val channels: ChannelRepository,
    private val settings: StateFlow<Settings>,
    private val activeUserId: StateFlow<String?>,
    private val visible: StateFlow<Boolean>,
    private val notifier: ChatNotifier,
    private val scope: CoroutineScope,
) {
    /**
     * Channels a live notification was posted for. Tapping one may add the channel to the list;
     * any other channel asked for from outside is not added, since any app can start Chatter.
     */
    private val notified = ConcurrentHashMap.newKeySet<String>()

    fun wasNotified(login: String): Boolean = login in notified

    /** What the last look found live, or null before the first one for [alertsFor]. */
    private var live: Set<String>? = null
    private var alertsFor: String? = null
    private var lastLook = 0L

    fun start() {
        val wanted = settings.map { it.liveNotifications }.distinctUntilChanged()
        val alertUser = combine(activeUserId, wanted) { userId, on -> userId.takeIf { on } }
        val looks = combine(alertUser, visible) { userId, onScreen -> userId to onScreen }.distinctUntilChanged()
        scope.launch {
            looks.collectLatest { (userId, onScreen) ->
                // Starting over knows nothing of what was live, so what already is does not count
                // as going live.
                if (userId != alertsFor) {
                    alertsFor = userId
                    live = null
                }
                if (userId == null && !onScreen) return@collectLatest
                val interval = if (onScreen) ON_SCREEN_POLL_MS else POLL_MS
                // Coming to the screen or leaving it does not ask again before it is due.
                delay((lastLook + interval - System.currentTimeMillis()).coerceAtLeast(0))
                while (true) {
                    lastLook = System.currentTimeMillis()
                    look(userId, onScreen)
                    delay(interval)
                }
            }
        }
    }

    private suspend fun look(userId: String?, onScreen: Boolean) {
        val list = channels.channels.value
        val choices = channels.liveAlerts.value
        // The tabs need the list's streams; in the background only a channel that notifies does.
        val listWanted = onScreen || (userId != null && anyWanted(choices, list))
        val listStreams = if (list.isEmpty() || !listWanted) emptyList() else {
            ask("Live status") { helix.liveStreams(list) }?.also { channels.applyLive(list, it) } ?: return
        }
        if (userId == null) return
        val followsReadable = TwitchScopes.allows(auth.account?.scopes, "user:read:follows")
        val followed = if (!followsReadable || !followedWanted(choices, list)) emptyList() else {
            // Unknown is not offline: what was live still counts as live.
            ask("Followed streams") { helix.followedStreams(userId) } ?: return
        }
        val streams = (followed + listStreams).filter { it.userId != userId }.associateBy { it.userLogin }
        for (login in wentLive(live, streams.keys)) {
            if (!choices.wanted(login, inList = login in list)) continue
            notified += login
            notifier.notifyLive(streams.getValue(login))
        }
        live = streams.keys
    }

    private suspend fun ask(what: String, request: suspend () -> List<HelixStream>): List<HelixStream>? = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "LiveAlerts"
        private const val POLL_MS = 3 * 60_000L
        private const val ON_SCREEN_POLL_MS = 2 * 60_000L

        /** Channels live now that were not before; none on the first look, with [before] null. */
        fun wentLive(before: Set<String>?, now: Set<String>): Set<String> = if (before == null) emptySet() else now - before

        /** Whether a channel in [list] notifies, so the list's streams are worth asking for. */
        fun anyWanted(choices: LiveAlertChoices, list: List<String>): Boolean = list.any { choices.wanted(it, inList = true) }

        /**
         * Whether the followed streams are worth asking for: only a followed channel outside [list]
         * that was turned on needs them, and by default none is.
         */
        fun followedWanted(choices: LiveAlertChoices, list: List<String>): Boolean = choices.on.any { it !in list }
    }
}
