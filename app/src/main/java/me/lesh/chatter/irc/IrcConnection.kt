package me.lesh.chatter.irc

import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.chat.ChatConnection
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
    /** What the phone and app are doing, for the log when the connection drops; see `AppWatch.situation`. */
    private val situation: () -> String = { "" },
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
    private val traffic = Traffic()
    // For the log when the connection drops: how far it got and what came last.
    @Volatile private var openedAt = 0L
    @Volatile private var connectedAt = 0L
    @Volatile private var linesThisConnection = 0
    @Volatile private var lastCommand: String? = null
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
        if (userChanged) {
            AppLog.i(TAG, if (login == ANONYMOUS_LOGIN) "Reading as a guest" else "Reading and writing with an account")
            closeSocket()
        }
        if (socket == null) openSocket()
    }

    /** Reads without an account: Twitch accepts any justinfan name without password, read-only. */
    fun connectAnonymously() = connect(ANONYMOUS_LOGIN, token = "")

    fun disconnect(): Unit = synchronized(lock) {
        if (wanted) AppLog.i(TAG, "Disconnecting")
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
        val networkUpBefore = networkUp
        val nowReachable = up && validated && !networkValidated
        networkUp = up
        networkValidated = validated
        if (cameBack) AppLog.i(TAG, "Network is back")
        if (!up && networkUpBefore) AppLog.i(TAG, "Network lost")
        if (nowReachable) AppLog.d(TAG, "Network has internet access")
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
        AppLog.i(TAG, "Skipping the wait after attempt $attempt, trying now")
        attempt = 0
        reconnectJob?.cancel()
        openSocket()
    }

    // While connecting, the JOINs are sent after the welcome; see onWelcome.
    override fun join(channel: String): Unit = synchronized(lock) {
        if (!isChannel(channel) || !joined.add(channel)) return
        AppLog.d(TAG, "Join ${AppLog.channel(channel)}")
        joins.add(channel)
        if (_state.value == ConnectionState.Connected) sendJoins()
    }

    override fun part(channel: String): Unit = synchronized(lock) {
        if (!isChannel(channel)) return
        AppLog.d(TAG, "Leave ${AppLog.channel(channel)}")
        joins.remove(channel)
        if (joined.remove(channel) && _state.value == ConnectionState.Connected) socket?.send("PART #$channel")
    }

    override fun sendMessage(channel: String, text: String, replyParentId: String?): Boolean {
        if (!isChannel(channel)) return false
        val tags = replyParentId?.let { "@reply-parent-msg-id=${IrcMessage.escapeTagValue(it)} " } ?: ""
        val clean = text.replace('\n', ' ').replace('\r', ' ')
        val sent = synchronized(lock) {
            _state.value == ConnectionState.Connected && socket?.send("${tags}PRIVMSG #$channel :$clean") == true
        }
        val what = (if (replyParentId != null) "a reply" else "a message") + " (${clean.length} characters)"
        if (sent) AppLog.d(TAG, "Sent $what in ${AppLog.channel(channel)}")
        else AppLog.w(TAG, "Could not send $what in ${AppLog.channel(channel)}: not connected")
        return sent
    }

    private fun openSocket() {
        val (login, token) = credentials ?: return
        _state.value = ConnectionState.Connecting
        AppLog.i(TAG, "Connecting to Twitch chat (attempt ${attempt + 1}, internet ${if (networkValidated) "confirmed" else "not confirmed by Android"})")
        openedAt = System.currentTimeMillis()
        connectedAt = 0L
        linesThisConnection = 0
        lastCommand = null
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
                lost("no welcome from Twitch within ${HANDSHAKE_TIMEOUT_MS / 1000} s")
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
        if (batch.isNotEmpty()) {
            ws.send("JOIN " + batch.joinToString(",") { "#$it" })
            AppLog.d(TAG, "Sent JOIN for ${batch.joinToString { AppLog.channel(it) }}")
        }
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
            AppLog.i(TAG, "Waiting for a network")
            _state.value = ConnectionState.WaitingForNetwork
            return
        }
        _state.value = ConnectionState.Connecting
        val delayMs = if (immediate) 0L else backoffMs(attempt, networkValidated) + Random.nextLong(0, 1000)
        attempt++
        AppLog.i(TAG, "Trying again in ${delayMs / 1000} s (attempt ${attempt + 1}${if (networkValidated) "" else ", internet not confirmed by Android, so waiting longer"})")
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
                    lost("no traffic for ${silence / 1000} s, the connection likely died unnoticed")
                    scheduleReconnect(immediate = true)
                    break
                }
                delay(SILENCE_LIMIT_MS - silence)
            }
        }
    }

    /**
     * Two lines for a dropped connection: why, how long it held and what came last, and what the
     * phone was doing. Most drops are the network or Android pausing the app, which this tells apart.
     */
    private fun lost(why: String, error: Throwable? = null) {
        val now = System.currentTimeMillis()
        val held = if (connectedAt > 0) {
            "connected for ${duration(now - connectedAt)}, $linesThisConnection lines, " +
                "last traffic ${(now - lastActivity) / 1000} s ago${lastCommand?.let { " ($it)" }.orEmpty()}"
        } else {
            "never got connected, ${(now - openedAt) / 1000} s after starting"
        }
        AppLog.w(TAG, "Connection lost: $why; $held", error)
        AppLog.i(TAG, "Situation: ${situation()}")
    }

    private inner class Listener(val login: String, val token: String) : WebSocketListener() {
        private fun isCurrent(ws: WebSocket) = synchronized(lock) { socket === ws }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastActivity = System.currentTimeMillis()
            val tls = response.handshake?.let { ", ${it.tlsVersion.javaName}" }.orEmpty()
            AppLog.d(TAG, "Socket open after ${lastActivity - openedAt} ms (${response.protocol}$tls), logging in")
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
                linesThisConnection++
                if (text.startsWith("PING", lineStart)) {
                    lastCommand = "PING"
                    webSocket.send("PONG" + text.substring(lineStart + 4, end))
                    AppLog.v(TAG, "PING answered")
                    continue
                }
                val msg = IrcMessage.parse(text, lineStart, end) ?: continue
                lastCommand = msg.command
                traffic.count(msg)?.let { AppLog.d(TAG, it) }
                if (msg.command != "PRIVMSG") AppLog.v(TAG, describe(msg))
                when (msg.command) {
                    "001" -> onWelcome(webSocket)
                    "ROOMSTATE" -> msg.channel?.let { synchronized(lock) { joins.answered(it) } }
                    "RECONNECT" -> {
                        lost("Twitch asked to reconnect, e.g. for a server restart")
                        scheduleReconnect(immediate = true)
                    }
                    "NOTICE" -> {
                        val channel = msg.channel
                        // The msg-id names the kind of notice; the text can quote a name.
                        val where = channel?.let { " in ${AppLog.channel(it)}" }.orEmpty()
                        val says = msg.trailing?.let { AppLog.hidden(": $it") }.orEmpty()
                        AppLog.d(TAG, "Notice ${msg.tag("msg-id") ?: "without id"}$where$says")
                        if (channel == null && isAuthFailure(msg.trailing)) {
                            AppLog.w(TAG, "Twitch refused the login")
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
                connectedAt = System.currentTimeMillis()
                AppLog.i(TAG, "Connected ${connectedAt - openedAt} ms after starting, joining ${joined.size} channel(s)")
                joins.restart(joined)
                sendJoins()
            }
            startWatchdog()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(webSocket)) return
            lost("Twitch closed it (code $code${reason.takeIf { it.isNotBlank() }?.let { ", \"$it\"" }.orEmpty()})")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A socket closed on purpose fails with "Canceled"; only the current one is news.
            if (!isCurrent(webSocket)) return
            lost("it failed${response?.let { " (HTTP ${it.code} ${it.message})" }.orEmpty()}", t)
            scheduleReconnect()
        }
    }

    /**
     * Counts what comes in and hands back a summary once a minute: one line per chat message would
     * push everything else out of the log.
     */
    private class Traffic {
        private val counts = HashMap<String, Int>()
        private val channels = HashSet<String>()
        private var since = System.currentTimeMillis()

        @Synchronized
        fun count(msg: IrcMessage): String? {
            counts.merge(msg.command, 1, Int::plus)
            msg.channel?.let(channels::add)
            val now = System.currentTimeMillis()
            if (now - since < SUMMARY_MS) return null
            val summary = counts.entries.sortedByDescending { it.value }.joinToString { "${it.value} ${it.key}" }
            val line = "Last ${(now - since) / 1000} s: $summary, in ${channels.size} channel(s)"
            counts.clear()
            channels.clear()
            since = now
            return line
        }
    }

    internal companion object {
        private const val TAG = "IrcConnection"
        private const val SUMMARY_MS = 60_000L

        private fun duration(millis: Long): String {
            val seconds = millis / 1000
            return when {
                seconds < 60 -> "$seconds s"
                seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
                else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
            }
        }

        /**
         * "← USERNOTICE in channel 2, msg-id=raid". The full log adds the tags, which name users;
         * a chat line's text never goes in.
         */
        private fun describe(msg: IrcMessage): String {
            val where = msg.channel?.let { " in ${AppLog.channel(it)}" }.orEmpty()
            val kind = msg.tag("msg-id")?.let { ", msg-id=$it" }.orEmpty()
            val tags = msg.tags.takeIf { it.isNotEmpty() }
                ?.entries?.joinToString(";") { "${it.key}=${it.value}" }
                ?.let { AppLog.hidden(" [${it.take(400)}]") }
                .orEmpty()
            return "← ${msg.command}$where$kind$tags"
        }

        private val CHANNEL = Regex("^[a-z0-9_]{1,25}$")

        /**
         * Whether [channel] is a plain Twitch login. Last check before the name goes into an IRC
         * line, where a space, comma or line break would change the command.
         */
        internal fun isChannel(channel: String): Boolean {
            if (CHANNEL.matches(channel)) return true
            AppLog.w(TAG, "Refused a channel name that is not a Twitch login")
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
