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
 * State of the chat connection. [WaitingForNetwork]: the phone has no network, nothing is tried
 * until one is back.
 */
enum class ConnectionState { Disconnected, Connecting, WaitingForNetwork, Connected, AuthFailed }

/**
 * One authenticated WebSocket to Twitch chat for reading and sending. Reconnects with exponential
 * backoff. Thread-safe.
 */
class IrcConnection(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Join order after a connect, lower first; see [JoinQueue]. */
    joinRank: (String) -> Int = { 0 },
) : ChatConnection {
    private val lock = Any()

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    // Unlimited so the socket thread never blocks; ChatRepository is the only consumer.
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
    private var joinJob: Job? = null
    private val joins = JoinQueue(System::currentTimeMillis, joinRank)
    @Volatile private var lastActivity = 0L
    /**
     * Retrying without a network cannot succeed; a night in flight mode would be thousands of DNS
     * lookups. [setNetwork] reconnects when it is back.
     */
    private var networkUp = true

    /**
     * Whether Android validated internet access. Unvalidated networks (captive portals, a hotspot
     * without signal) are still tried, since some work without passing the check, but with the long
     * backoff; see [backoffMs].
     */
    private var networkValidated = true

    /**
     * Connects, or keeps the existing connection. A new token for the same user is only used on the
     * next reconnect; the running session stays valid.
     */
    fun connect(login: String, token: String): Unit = synchronized(lock) {
        val userChanged = credentials?.first != login
        credentials = login to token
        wanted = true
        if (userChanged) closeSocket()
        if (socket == null) openSocket()
    }

    /** Reads without an account: Twitch accepts any justinfan name without password, read-only. */
    fun connectAnonymously() = connect(ANONYMOUS_LOGIN, token = "")

    fun disconnect(): Unit = synchronized(lock) {
        wanted = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        handshakeJob?.cancel()
        closeSocket()
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Network changes. Coming back reconnects right away, losing the network stops retries, and a
     * network that turns out to be validated ends a long backoff.
     *
     * Android repeats capability callbacks for every signal change, so only changes of these two
     * values do anything.
     */
    fun setNetwork(up: Boolean, validated: Boolean): Unit = synchronized(lock) {
        val cameBack = up && !networkUp
        val nowReachable = up && validated && !networkValidated
        networkUp = up
        networkValidated = validated
        if (!wanted || _state.value == ConnectionState.Connected) return
        when {
            // Not connected, so any socket is a connection attempt on the network that just went
            // away.
            !up -> {
                reconnectJob?.cancel()
                closeSocket()
                _state.value = ConnectionState.WaitingForNetwork
            }
            // A socket from the old network is likely dead; start over on the new one.
            cameBack -> {
                attempt = 0
                reconnectJob?.cancel()
                closeSocket()
                openSocket()
            }
            // Only while waiting between attempts; one in progress is left to finish.
            nowReachable && socket == null -> retryNow()
        }
    }

    /**
     * Skips the backoff, for when the app is opened and would otherwise show "Connecting…" for up
     * to five minutes.
     */
    fun retryNow(): Unit = synchronized(lock) {
        if (!wanted || !networkUp || socket != null || _state.value == ConnectionState.Connected) return
        attempt = 0
        reconnectJob?.cancel()
        openSocket()
    }

    // While connecting, the JOINs are sent after the welcome; see onWelcome.
    override fun join(channel: String): Unit = synchronized(lock) {
        if (!isChannel(channel) || !joined.add(channel)) return
        joins.add(channel)
        if (_state.value == ConnectionState.Connected) sendJoins()
    }

    override fun part(channel: String): Unit = synchronized(lock) {
        if (!isChannel(channel)) return
        joins.remove(channel)
        if (joined.remove(channel) && _state.value == ConnectionState.Connected) socket?.send("PART #$channel")
    }

    override fun sendMessage(channel: String, text: String, replyParentId: String?): Boolean {
        if (!isChannel(channel)) return false
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
     * Reconnects if Twitch does not answer the login with its welcome in time.
     *
     * The socket has no read timeout and OkHttp sends no pings, to keep traffic low. Once
     * connected, [startWatchdog] detects silence; before that, a network that dies mid-handshake
     * would leave a socket that neither fails nor answers.
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

    /**
     * Sends the JOINs [joins] allows now and schedules the next batch. Called under [lock]. One
     * JOIN line can name many channels; each counts against the limit.
     */
    private fun sendJoins() {
        joinJob?.cancel()
        val ws = socket ?: return
        val batch = joins.take()
        if (batch.isNotEmpty()) ws.send("JOIN " + batch.joinToString(",") { "#$it" })
        val due = joins.nextDueAt() ?: return
        joinJob = scope.launch {
            delay((due - System.currentTimeMillis()).coerceAtLeast(0))
            synchronized(lock) { if (socket === ws && _state.value == ConnectionState.Connected) sendJoins() }
        }
    }

    private fun closeSocket() {
        handshakeJob?.cancel()
        joinJob?.cancel()
        socket?.cancel()
        socket = null
    }

    private fun scheduleReconnect(immediate: Boolean = false): Unit = synchronized(lock) {
        if (!wanted || reconnectJob?.isActive == true) return
        closeSocket()
        // setNetwork reconnects once the network is back.
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
     * Twitch sends a PING about every 5 minutes. Longer silence means the connection died
     * unnoticed, e.g. after a silent network switch.
     *
     * Sleeps until the silence could exceed the limit instead of polling, so a connected night
     * costs a handful of wakeups.
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
            if (token.isNotEmpty()) webSocket.send("PASS oauth:$token")
            webSocket.send("NICK $login")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            lastActivity = System.currentTimeMillis()
            // Walked instead of split; each message keeps referring to the frame (see IrcMessage).
            var start = 0
            while (start < text.length) {
                val end = text.indexOf("\r\n", start).let { if (it == -1) text.length else it }
                val lineStart = start
                start = end + 2
                if (end == lineStart) continue
                // PINGs are answered on the socket thread before any parsing.
                if (text.startsWith("PING", lineStart)) {
                    webSocket.send("PONG" + text.substring(lineStart + 4, end))
                    continue
                }
                val msg = IrcMessage.parse(text, lineStart, end) ?: continue
                when (msg.command) {
                    "001" -> onWelcome(webSocket)
                    "ROOMSTATE" -> msg.channel?.let { synchronized(lock) { joins.answered(it) } }
                    "RECONNECT" -> scheduleReconnect(immediate = true)
                    "NOTICE" -> {
                        val channel = msg.channel
                        if (channel == null && isAuthFailure(msg.trailing)) {
                            synchronized(lock) {
                                wanted = false
                                closeSocket()
                            }
                            _state.value = ConnectionState.AuthFailed
                        }
                        // A suspended channel never answers its JOIN; retrying is pointless.
                        if (channel != null && msg.tag("msg-id") == "msg_channel_suspended") {
                            synchronized(lock) { joins.answered(channel) }
                        }
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
                joins.restart(joined)
                sendJoins()
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

        private val CHANNEL = Regex("^[a-z0-9_]{1,25}$")

        /**
         * Whether [channel] is a plain Twitch login. Last check before the name goes into an IRC
         * line, where a space, comma or line break would change the command.
         */
        internal fun isChannel(channel: String): Boolean {
            if (CHANNEL.matches(channel)) return true
            Log.w(TAG, "Refused a channel name that is not a Twitch login")
            return false
        }
        private const val ANONYMOUS_LOGIN = "justinfan12345"

        /**
         * Wait before the next attempt after [attempt] failures. Doubles up to 30 seconds, enough
         * for a dropped connection. If it keeps failing on a working network (login page, firewall)
         * the wait grows to five minutes, to spare the radio. Unvalidated networks get the long
         * wait from the start.
         */
        fun backoffMs(attempt: Int, validated: Boolean): Long {
            if (!validated) return (1000L shl attempt.coerceIn(0, 9)).coerceAtMost(MAX_BACKOFF_MS)
            if (attempt < PATIENT_AFTER) return (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(SHORT_BACKOFF_MS)
            return (SHORT_BACKOFF_MS shl (attempt - PATIENT_AFTER + 1).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)
        }

        private const val SHORT_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L

        /** Failed attempts before the wait grows past [SHORT_BACKOFF_MS]; about five minutes. */
        private const val PATIENT_AFTER = 14

        /** How long Twitch has to answer the login. */
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L

        /** Silence after which a connected socket counts as dead. */
        private const val SILENCE_LIMIT_MS = 6 * 60_000L

        private fun isAuthFailure(text: String?) = text != null &&
            (text.contains("authentication failed", ignoreCase = true) ||
                text.contains("Improperly formatted auth", ignoreCase = true))
    }
}
