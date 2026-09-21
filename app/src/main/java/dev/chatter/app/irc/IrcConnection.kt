package dev.chatter.app.irc

import android.util.Log
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

enum class ConnectionState { Disconnected, Connecting, Connected, AuthFailed }

/**
 * A single authenticated WebSocket connection to Twitch chat that is used both
 * for reading and sending. Reconnects automatically with exponential backoff.
 *
 * All public methods are thread-safe.
 */
class IrcConnection(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val lock = Any()

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    // Unlimited so the socket thread never blocks; there is exactly one consumer (ChatRepository).
    private val incoming = Channel<IrcMessage>(Channel.UNLIMITED)
    val messages: Flow<IrcMessage> = incoming.receiveAsFlow()

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
     * couple of thousand of them. [setNetworkAvailable] brings the connection back instead.
     */
    private var networkUp = true

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
     * What the phone's network is doing. Coming back skips the backoff and reconnects right away;
     * going away stops the retries until it does, because there is nothing to connect to.
     */
    fun setNetworkAvailable(up: Boolean): Unit = synchronized(lock) {
        networkUp = up
        if (!wanted) return
        if (!up) {
            reconnectJob?.cancel()
            return
        }
        if (_state.value != ConnectionState.Connected) {
            attempt = 0
            reconnectJob?.cancel()
            closeSocket()
            openSocket()
        }
    }

    // While connecting, the JOINs are sent after the welcome message (see onWelcome).
    fun join(channel: String): Unit = synchronized(lock) {
        if (joined.add(channel) && _state.value == ConnectionState.Connected) socket?.send("JOIN #$channel")
    }

    fun part(channel: String): Unit = synchronized(lock) {
        if (joined.remove(channel) && _state.value == ConnectionState.Connected) socket?.send("PART #$channel")
    }

    /** Returns false if the message could not be handed to the socket. */
    fun sendMessage(channel: String, text: String, replyParentId: String? = null): Boolean {
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
        _state.value = ConnectionState.Connecting
        // Without a network there is nothing to retry against; setNetworkAvailable brings us back.
        if (!networkUp) return
        val delayMs = if (immediate) 0L else {
            val base = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
            base + Random.nextLong(0, 1000)
        }
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) { if (wanted && socket == null) openSocket() }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            // Twitch sends a PING roughly every 5 minutes. If nothing arrives for longer, the
            // connection is dead without us noticing (e.g. after a silent network switch).
            while (isActive) {
                delay(60_000)
                if (System.currentTimeMillis() - lastActivity > 6 * 60_000) {
                    Log.i(TAG, "No traffic for 6 minutes, reconnecting")
                    scheduleReconnect(immediate = true)
                    break
                }
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

    private companion object {
        const val TAG = "IrcConnection"

        /** How long Twitch has to answer the login before the socket counts as dead. */
        const val HANDSHAKE_TIMEOUT_MS = 20_000L

        fun isAuthFailure(text: String?) = text != null &&
            (text.contains("authentication failed", ignoreCase = true) ||
                text.contains("Improperly formatted auth", ignoreCase = true))
    }
}
