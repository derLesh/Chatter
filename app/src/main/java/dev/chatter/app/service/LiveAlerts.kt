package dev.chatter.app.service

import android.util.Log
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.auth.TwitchScopes
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.net.HelixApi
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
 * Notifies when a channel goes live: by default the channels in the list, and any other followed
 * channel the user turned on. Asks Twitch every few minutes, also in the background, since the
 * moment a stream starts is the moment somebody wants its chat.
 */
class LiveAlerts(
    private val helix: HelixApi,
    private val auth: AuthRepository,
    private val channels: ChannelRepository,
    private val settings: StateFlow<Settings>,
    private val activeUserId: StateFlow<String?>,
    private val notifier: ChatNotifier,
    private val scope: CoroutineScope,
) {
    /**
     * Channels a live notification was posted for. Tapping one may add the channel to the list;
     * any other channel asked for from outside is not added, since any app can start Chatter.
     */
    private val notified = ConcurrentHashMap.newKeySet<String>()

    fun wasNotified(login: String): Boolean = login in notified

    fun start() {
        val wanted = settings.map { it.liveNotifications }.distinctUntilChanged()
        scope.launch {
            combine(activeUserId, wanted) { userId, on -> userId.takeIf { on } }.collectLatest { userId ->
                if (userId == null) return@collectLatest
                // Starting over knows nothing of what was live, so what already is does not count
                // as going live.
                var live: Set<String>? = null
                while (true) {
                    live = poll(userId, live)
                    delay(POLL_MS)
                }
            }
        }
    }

    /** Notifies about what went live since [before], and returns what is live now. */
    private suspend fun poll(userId: String, before: Set<String>?): Set<String>? {
        val list = channels.channels.value
        val streams = try {
            val followsReadable = TwitchScopes.allows(auth.account?.scopes, "user:read:follows")
            (if (followsReadable) helix.followedStreams(userId) else emptyList()) + helix.liveStreams(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live status failed: ${e.message}")
            // Unknown is not offline: what was live still counts as live.
            return before
        }.filter { it.userId != userId }.associateBy { it.userLogin }
        val choices = channels.liveAlerts.value
        for (login in wentLive(before, streams.keys)) {
            if (!choices.wanted(login, inList = login in list)) continue
            notified += login
            notifier.notifyLive(streams.getValue(login))
        }
        return streams.keys
    }

    companion object {
        private const val TAG = "LiveAlerts"
        private const val POLL_MS = 3 * 60_000L

        /** Channels live now that were not before; none on the first look, with [before] null. */
        fun wentLive(before: Set<String>?, now: Set<String>): Set<String> = if (before == null) emptySet() else now - before
    }
}
