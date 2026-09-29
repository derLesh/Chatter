package dev.chatter.app.irc

import android.util.Log
import dev.chatter.app.chat.ChatConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * Where the chat connection stands. [WaitingForNetwork] is the phone having no network at all:
 * nothing is tried until one turns up.
 */
enum class ConnectionState { Disconnected, Connecting, WaitingForNetwork, Connected, AuthFailed }

/**
 * A single authenticated WebSocket connection to Twitch chat that is used both
 * for reading and sending. Reconnects automatically with exponential backoff.
 *
 * All public methods are thread-safe.
 */
class IrcConnection(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) : ChatConnection {
    private val lock = Any()

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    // Unlimited so the socket thread never blocks; there is exactly one consumer (ChatRepository).
    private val incoming = Channel<IrcMessage>(Channel.UNLIMITED)
    override val messages: Flow<IrcMessage> = incoming.receiveAsFlow()

    private var socket: WebSocket? = null
    private var credentials: Pair<String, String>? = null // login to token
    private val joined = LinkedHashSet<String>()
    private var wanted = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var handshakeJob: Job? = null
    @Volatile private var lastActivity = 0L
    /**
     * Whether the phone has a network at all. Retrying without one is a DNS lookup that cannot
     * succeed, and the backoff tops out at half a minute, so a night in flight mode would be a
     * couple of thousand of them. [setNetwork] brings the connection back instead.
     */
    private var networkUp = true

    /**
     * Whether Android found that the network reaches the internet. A network without — a hotel
     * Wi-Fi behind its login page, a hotspot whose phone lost its signal — is still tried, since
     * some networks work without ever passing Android's check, but patiently: see [backoffMs].
     */
    private var networkValidated = true

    /**
     * Connects (or keeps the existing connection). A new token for the same user is only stored
     * for the next reconnect: an authenticated connection stays valid when the token is renewed.
     */
    fun connect(login: String, token: String): Unit = synchronized(lock) {
        val userChanged = credentials?.first != login
        credentials = login to token
        wanted = true
        if (userChanged) closeSocket()
        if (socket == null) openSocket()
    }

    fun disconnect(): Unit = synchronized(lock) {
        wanted = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        handshakeJob?.cancel()
        closeSocket()
        _state.value = ConnectionState.Disconnected
    }

    /**
     * What the phone's network is doing: whether there is one, and whether Android found that it
     * reaches the internet. Coming back skips the backoff and reconnects right away; going away
     * stops the retries until it does, because there is nothing to connect to. A network that
     * turns out to reach the internet after all ends a long wait at once.
     *
     * Android reports the capabilities of a network again and again — every change of signal
     * strength is one — so only a change of the two things asked for here does anything.
     */
    fun setNetwork(up: Boolean, validated: Boolean): Unit = synchronized(lock) {
        val cameBack = up && !networkUp
        val nowReachable = up && validated && !networkValidated
        networkUp = up
        networkValidated = validated
        if (!wanted || _state.value == ConnectionState.Connected) return
        when {
            // Not connected, so a socket there is a try under way, on a network that just went.
            !up -> {
                reconnectJob?.cancel()
                closeSocket()
                _state.value = ConnectionState.WaitingForNetwork
            }
            // A socket opened on the network before is likely dead: start over on the new one.
            cameBack -> {
                attempt = 0
                reconnectJob?.cancel()
                closeSocket()
                openSocket()
            }
            // Only while waiting between two tries; one under way is left to finish.
            nowReachable && socket == null -> retryNow()
        }
    }

    /**
     * Tries again at once instead of waiting out the backoff, for the moment somebody opens the
     * app and would otherwise look at "Connecting…" for up to five minutes.
     */
    fun retryNow(): Unit = synchronized(lock) {
        if (!wanted || !networkUp || socket != null || _state.value == ConnectionState.Connected) return
        attempt = 0
        reconnectJob?.cancel()
        openSocket()
    }

    // While connecting, the JOINs are sent after the welcome message (see onWelcome).
    override fun join(channel: String): Unit = synchronized(lock) {
        if (joined.add(channel) && _state.value == ConnectionState.Connected) socket?.send("JOIN #$channel")
    }

    override fun part(channel: String): Unit = synchronized(lock) {
        if (joined.remove(channel) && _state.value == ConnectionState.Connected) socket?.send("PART #$channel")
    }

    override fun sendMessage(channel: String, text: String, replyParentId: String?): Boolean {
        val tags = replyParentId?.let { "@reply-parent-msg-id=${IrcMessage.escapeTagValue(it)} " } ?: ""
        val clean = text.replace('\n', ' ').replace('\r', ' ')
        return synchronized(lock) {
            _state.value == ConnectionState.Connected && socket?.send("${tags}PRIVMSG #$channel :$clean") == true
        }
    }

    private fun openSocket() {
        val (login, token) = credentials ?: return
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        socket = http.newWebSocket(request, Listener(login, token))
        startHandshakeTimeout()
    }

    /**
     * Gives Twitch a moment to answer the login with its welcome, and reconnects if it does not.
     *
     * The socket has no read timeout — a quiet chat is normal — and OkHttp sends no pings of its
     * own, because a ping every half minute all day is exactly the kind of traffic this app tries
     * not to make. Once the connection stands, [startWatchdog] notices silence. Until then this
     * is the only thing that would: a network that dies between the socket opening and the
     * welcome leaves a socket that is never reported as failed and never answers either.
     */
    private fun startHandshakeTimeout() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (_state.value != ConnectionState.Connected) {
                Log.i(TAG, "No welcome within ${HANDSHAKE_TIMEOUT_MS / 1000}s, reconnecting")
                scheduleReconnect()
            }
        }
    }

    private fun closeSocket() {
        handshakeJob?.cancel()
        socket?.cancel()
        socket = null
    }

    private fun scheduleReconnect(immediate: Boolean = false): Unit = synchronized(lock) {
        if (!wanted || reconnectJob?.isActive == true) return
        closeSocket()
        // Without a network there is nothing to retry against; setNetwork brings us back.
        if (!networkUp) {
            _state.value = ConnectionState.WaitingForNetwork
            return
        }
        _state.value = ConnectionState.Connecting
        val delayMs = if (immediate) 0L else backoffMs(attempt, networkValidated) + Random.nextLong(0, 1000)
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) { if (wanted && socket == null) openSocket() }
        }
    }

    /**
     * Twitch sends a PING roughly every 5 minutes. If nothing arrives for longer, the connection
     * is dead without us noticing (e.g. after a silent network switch).
     *
     * This sleeps exactly until the silence could have become long enough, rather than looking
     * every minute: a night connected is then a handful of wakeups instead of several hundred,
     * and a dead socket is noticed sooner, because the check falls on the moment it is due.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                val silence = System.currentTimeMillis() - lastActivity
                if (silence >= SILENCE_LIMIT_MS) {
                    Log.i(TAG, "No traffic for ${SILENCE_LIMIT_MS / 60_000} minutes, reconnecting")
                    scheduleReconnect(immediate = true)
                    break
                }
                delay(SILENCE_LIMIT_MS - silence)
            }
        }
    }

    private inner class Listener(val login: String, val token: String) : WebSocketListener() {
        private fun isCurrent(ws: WebSocket) = synchronized(lock) { socket === ws }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastActivity = System.currentTimeMillis()
            webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
            webSocket.send("PASS oauth:$token")
            webSocket.send("NICK $login")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            lastActivity = System.currentTimeMillis()
            for (line in text.split("\r\n")) {
                if (line.isEmpty()) continue
                // Answer PINGs directly on the socket thread, before any parsing work.
                if (line.startsWith("PING")) {
                    webSocket.send("PONG" + line.substring(4))
                    continue
                }
                val msg = IrcMessage.parse(line) ?: continue
                when (msg.command) {
                    "001" -> onWelcome(webSocket)
                    "RECONNECT" -> scheduleReconnect(immediate = true)
                    "NOTICE" -> if (msg.channel == null && isAuthFailure(msg.trailing)) {
                        synchronized(lock) {
                            wanted = false
                            closeSocket()
                        }
                        _state.value = ConnectionState.AuthFailed
                    }
                }
                incoming.trySend(msg)
            }
        }

        private fun onWelcome(webSocket: WebSocket) {
            synchronized(lock) {
                handshakeJob?.cancel()
                attempt = 0
                _state.value = ConnectionState.Connected
                // Twitch allows joining many channels in one command.
                joined.chunked(20).forEach { batch ->
                    webSocket.send("JOIN " + batch.joinToString(",") { "#$it" })
                }
            }
            startWatchdog()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(webSocket)) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Socket failure: ${t.message}")
            if (isCurrent(webSocket)) scheduleReconnect()
        }
    }

    internal companion object {
        private const val TAG = "IrcConnection"

        /**
         * How long to wait before the next try after [attempt] failed ones. Doubling up to half a
         * minute, which is what a dropped connection on a working network needs. Past that the
         * network is working but Twitch cannot be reached through it — a login page, a firewall —
         * and trying every half minute all night is a TLS handshake and an awake radio each time
         * for nothing, so the wait keeps doubling up to five minutes. A network Android could not
         * get through to the internet is waited on like that from the start.
         */
        fun backoffMs(attempt: Int, validated: Boolean): Long {
            if (!validated) return (1000L shl attempt.coerceIn(0, 9)).coerceAtMost(MAX_BACKOFF_MS)
            if (attempt < PATIENT_AFTER) return (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(SHORT_BACKOFF_MS)
            return (SHORT_BACKOFF_MS shl (attempt - PATIENT_AFTER + 1).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)
        }

        private const val SHORT_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L

        /** Failed tries on a working network before the wait grows past [SHORT_BACKOFF_MS]; a good five minutes. */
        private const val PATIENT_AFTER = 14

        /** How long Twitch has to answer the login before the socket counts as dead. */
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L

        /** How long a connected socket may say nothing at all before it counts as dead. */
        private const val SILENCE_LIMIT_MS = 6 * 60_000L

        private fun isAuthFailure(text: String?) = text != null &&
            (text.contains("authentication failed", ignoreCase = true) ||
                text.contains("Improperly formatted auth", ignoreCase = true))
    }
}
