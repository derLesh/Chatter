package dev.chatter.app.service

import android.util.Log
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.auth.TwitchScopes
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixStream
import dev.chatter.app.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
        scope.launch {
            activeUserId.collectLatest { userId ->
                if (userId == null) return@collectLatest
                // A new account starts without knowing what was live, so nothing that is already
                // live counts as going live.
                var live: Set<String>? = null
                while (true) {
                    live = if (settings.value.liveNotifications) poll(userId, live) else null
                    delay(POLL_MS)
                }
            }
        }
    }

    /** Asks what is live now, notifies about what went live since [before], returns what is live. */
    private suspend fun poll(userId: String, before: Set<String>?): Set<String>? {
        val list = channels.channels.value
        val streams = try {
            val followed = if (TwitchScopes.allows(auth.account?.scopes, "user:read:follows")) helix.followedStreams(userId) else emptyList()
            followed + helix.liveStreams(list)
        } catch (e: Exception) {
            Log.w(TAG, "Live status failed: ${e.message}")
            // Unknown, not offline: whatever was live is still taken as live.
            return before
        }.filter { it.userId != userId }.associateBy { it.userLogin }
        val alerts = channels.liveAlerts.value
        for (login in wentLive(before, streams.keys)) {
            if (!alerts.wanted(login, inList = login in list)) continue
            val stream = streams.getValue(login)
            notified += login
            notifier.notifyLive(stream)
        }
        return streams.keys
    }

    companion object {
        private const val TAG = "LiveAlerts"
        private const val POLL_MS = 3 * 60_000L

        /** Channels live now that were not before; nothing on the first look, when [before] is null. */
        fun wentLive(before: Set<String>?, now: Set<String>): Set<String> = if (before == null) emptySet() else now - before
    }
}

/** For the notification: the stream's channel name, falling back to the login. */
val HelixStream.channelName: String get() = userName.ifEmpty { userLogin }
