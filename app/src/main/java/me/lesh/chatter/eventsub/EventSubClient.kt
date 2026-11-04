package me.lesh.chatter.eventsub

import android.util.Log
import me.lesh.chatter.net.EventSubApi
import me.lesh.chatter.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/** What to be told about: an EventSub type in one version, for one [condition]. */
data class EventSubSubscription(val type: String, val version: String, val condition: Map<String, String>)

/**
 * The connection to Twitch's EventSub socket, for one account.
 *
 * Twitch closes a socket that has no subscription ten seconds after its welcome, and subscriptions
 * are made over Helix, naming the session. So the socket is only open while there is something to
 * subscribe to, and every new session subscribes again; a `session_reconnect` keeps them.
 *
 * Keepalives come every [KEEPALIVE_SECONDS] when nothing else does, like the IRC server's PING, so
 * the phone is not woken more often than the chat already wakes it. A silent network change leaves
 * a dead socket, so a watchdog reconnects once a keepalive is overdue.
 */
class EventSubClient(
    private val http: OkHttpClient,
    private val api: EventSubApi,
    private val scope: CoroutineScope,
) {
    private val lock = Any()

    // --- guarded by lock ---
    private var owner: String? = null
    private var wanted: List<EventSubSubscription> = emptyList()
    private var socket: WebSocket? = null
    /** The socket Twitch moves the session to; it replaces [socket] with its welcome. */
    private var movingTo: WebSocket? = null
    private var sessionId: String? = null
    /** Subscriptions of the current session, with Twitch's id for each. */
    private val active = HashMap<EventSubSubscription, String>()
    /** Twitch said no, e.g. the user is no longer a mod there; not asked again while wanted. */
    private val refused = HashSet<EventSubSubscription>()
    private val seenMessages = LinkedHashSet<String>()
    private var networkUp = true
    /** Twitch closed an unused socket; nothing is tried until the subscriptions change. */
    private var idle = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile private var lastActivity = 0L
    @Volatile private var silenceLimitMs = WELCOME_TIMEOUT_MS

    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val requests = Semaphore(PARALLEL_REQUESTS)

    private val _events = MutableSharedFlow<EventSubEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EventSubEvent> = _events

    init {
        scope.launch { for (request in syncRequests) sync() }
    }

    /**
     * What [owner]'s socket should listen for, most wanted first; past Twitch's limit the rest is
     * left out. Another owner, or none, ends the session.
     */
    fun update(owner: String?, subscriptions: List<EventSubSubscription>) = synchronized(lock) {
        if (owner != this.owner) {
            closeSockets()
            refused.clear()
            this.owner = owner
        }
        val next = subscriptions.take(MAX_SUBSCRIPTIONS)
        if (next != wanted) {
            wanted = next
            idle = false
            refused.retainAll(next.toHashSet())
        }
        when {
            owner == null || wanted.isEmpty() -> closeSockets()
            socket == null -> if (networkUp && !idle && reconnectJob?.isActive != true) open(URL)
            sessionId != null -> syncRequests.trySend(Unit)
        }
    }

    /** Losing the network stops retries; when it is back the session starts over. */
    fun setNetwork(up: Boolean) = synchronized(lock) {
        val cameBack = up && !networkUp
        networkUp = up
        if (!up) {
            closeSockets()
        } else if (cameBack && owner != null && wanted.isNotEmpty()) {
            closeSockets()
            attempt = 0
            idle = false
            open(URL)
        }
    }

    private fun closeSockets() {
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        socket?.close(NORMAL_CLOSE, null)
        movingTo?.close(NORMAL_CLOSE, null)
        socket = null
        movingTo = null
        sessionId = null
        active.clear()
    }

    private fun open(url: String) {
        sessionId = null
        active.clear()
        lastActivity = System.currentTimeMillis()
        silenceLimitMs = WELCOME_TIMEOUT_MS
        val ws = http.newWebSocket(Request.Builder().url(url).build(), Listener())
        socket = ws
        startWatchdog(ws)
    }

    /** Reconnects once [ws] has been silent for longer than Twitch promised. */
    private fun startWatchdog(ws: WebSocket) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                val silence = System.currentTimeMillis() - lastActivity
                if (silence >= silenceLimitMs) {
                    Log.i(TAG, "Silent for ${silence / 1000}s, reconnecting")
                    synchronized(lock) { if (socket === ws) scheduleReconnect() }
                    break
                }
                delay(silenceLimitMs - silence)
            }
        }
    }

    private fun scheduleReconnect() {
        socket?.cancel()
        movingTo?.cancel()
        socket = null
        movingTo = null
        sessionId = null
        active.clear()
        watchdogJob?.cancel()
        if (owner == null || wanted.isEmpty() || !networkUp || idle || reconnectJob?.isActive == true) return
        val delayMs = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(MAX_BACKOFF_MS) + Random.nextLong(0, 1000)
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) { if (socket == null && owner != null && wanted.isNotEmpty() && networkUp) open(URL) }
        }
    }

    /**
     * Brings the session's subscriptions in line with [wanted], a few requests at a time. A new
     * session has ten seconds for its first one, so this starts right at the welcome.
     */
    private suspend fun sync() {
        val (session, toAdd, toDrop) = synchronized(lock) {
            val session = sessionId ?: return
            val wantedSet = wanted.toHashSet()
            Triple(
                session,
                wanted.filter { it !in active && it !in refused },
                active.filterKeys { it !in wantedSet },
            )
        }
        if (toAdd.isEmpty() && toDrop.isEmpty()) return
        var retryLater = false
        coroutineScope {
            toDrop.forEach { (subscription, id) ->
                // Gone from the session either way: it ends with the socket.
                synchronized(lock) { active.remove(subscription) }
                if (id.isNotEmpty()) launch { requests.withPermit { runCatching { api.unsubscribe(id) } } }
            }
            toAdd.forEach { subscription ->
                launch {
                    requests.withPermit {
                        if (synchronized(lock) { sessionId != session || subscription !in wanted }) return@withPermit
                        val outcome = subscribe(subscription, session)
                        synchronized(lock) {
                            when {
                                sessionId != session -> Unit
                                outcome is Subscribed -> active[subscription] = outcome.id
                                outcome == Refused -> refused += subscription
                                else -> retryLater = true
                            }
                        }
                    }
                }
            }
        }
        if (retryLater) scope.launch {
            delay(RETRY_MS)
            syncRequests.trySend(Unit)
        }
    }

    private sealed interface Outcome
    private class Subscribed(val id: String) : Outcome
    private data object Refused : Outcome
    private data object Failed : Outcome

    private suspend fun subscribe(subscription: EventSubSubscription, session: String): Outcome = try {
        Subscribed(api.subscribe(subscription.type, subscription.version, subscription.condition, session))
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        when {
            // Already there for this session; without its id it cannot be ended early.
            e.code == 409 -> Subscribed("")
            e.code == 429 || e.code >= 500 -> Failed
            else -> {
                Log.d(TAG, "${subscription.type} refused: ${e.apiMessage ?: e.code}")
                Refused
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "${subscription.type} failed: ${e.message}")
        Failed
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) { if (webSocket !== socket && webSocket !== movingTo) return }
            lastActivity = System.currentTimeMillis()
            when (val frame = EventSubFrame.parse(text)) {
                is EventSubFrame.Welcome -> welcome(webSocket, frame)
                is EventSubFrame.Notification -> {
                    // Twitch may send a message twice.
                    val fresh = synchronized(lock) {
                        seenMessages.add(frame.messageId).also {
                            if (seenMessages.size > MESSAGES_REMEMBERED) seenMessages.remove(seenMessages.first())
                        }
                    }
                    if (fresh) frame.event?.let { _events.tryEmit(it) }
                }
                is EventSubFrame.Reconnect -> synchronized(lock) {
                    if (webSocket === socket && movingTo == null) {
                        movingTo = http.newWebSocket(Request.Builder().url(frame.url).build(), this)
                    }
                }
                is EventSubFrame.Revocation -> synchronized(lock) {
                    active.entries.firstOrNull { it.value == frame.subscriptionId }?.let {
                        active.remove(it.key)
                        refused += it.key
                    }
                }
                EventSubFrame.Keepalive, null -> Unit
            }
        }

        private fun welcome(webSocket: WebSocket, frame: EventSubFrame.Welcome) = synchronized(lock) {
            if (webSocket === movingTo) {
                // The subscriptions came along; the old socket can go.
                socket?.close(NORMAL_CLOSE, null)
                socket = webSocket
                movingTo = null
            } else {
                active.clear()
            }
            sessionId = frame.sessionId
            attempt = 0
            silenceLimitMs = (frame.keepaliveSeconds ?: KEEPALIVE_SECONDS) * 1000L + KEEPALIVE_GRACE_MS
            startWatchdog(webSocket)
            syncRequests.trySend(Unit)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE, null)
            synchronized(lock) {
                if (webSocket === movingTo) {
                    movingTo = null
                } else if (webSocket === socket) {
                    // Twitch refused everything; trying again would only end the same way. A slow
                    // network that missed the ten seconds tries again.
                    if (code == UNUSED_CLOSE && wanted.all { it in refused }) {
                        Log.i(TAG, "Closed as unused, waiting for other subscriptions")
                        idle = true
                    }
                    scheduleReconnect()
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "EventSub failed: ${t.message}")
            synchronized(lock) {
                if (webSocket === movingTo) movingTo = null
                else if (webSocket === socket) scheduleReconnect()
            }
        }
    }

    private companion object {
        const val TAG = "EventSub"

        /** Twitch allows 10 to 600; the IRC server's PING comes about this often too. */
        const val KEEPALIVE_SECONDS = 300
        const val URL = "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=$KEEPALIVE_SECONDS"

        const val WELCOME_TIMEOUT_MS = 15_000L
        const val KEEPALIVE_GRACE_MS = 15_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val RETRY_MS = 30_000L

        /** Twitch's limit per socket. */
        const val MAX_SUBSCRIPTIONS = 300

        /** OkHttp runs five requests to one host at a time; this leaves room for the chat's. */
        const val PARALLEL_REQUESTS = 3

        const val MESSAGES_REMEMBERED = 128
        const val NORMAL_CLOSE = 1000

        /** Twitch's close code for a socket without subscriptions. */
        const val UNUSED_CLOSE = 4003
    }
}
