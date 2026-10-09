package dev.chatter.app.emotes

import android.util.Log
import dev.chatter.app.badges.NamePaint
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.SevenTvActiveEmote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/** A change to a 7TV emote set or user, pushed by the EventAPI. */
sealed interface SevenTvEvent {
    val actor: String?

    data class EmoteSetUpdate(
        val setId: String,
        override val actor: String?,
        val added: List<SevenTvActiveEmote>,
        val removed: List<SevenTvActiveEmote>,
        /** Old and new version of each renamed emote. */
        val renamed: List<Pair<SevenTvActiveEmote, SevenTvActiveEmote>>,
    ) : SevenTvEvent

    /** The channel switched to another emote set. */
    data class ActiveSetChanged(val userId: String, override val actor: String?, val newSetId: String?) : SevenTvEvent

    /**
     * A badge 7TV described for someone in a joined channel. [EntitlementChanged] says who wears
     * it.
     */
    data class BadgeCreated(val id: String, val name: String, val tooltip: String) : SevenTvEvent {
        override val actor: String? get() = null
    }

    /** A paint 7TV described; like a badge, [EntitlementChanged] says who wears it. */
    data class PaintCreated(val paint: NamePaint) : SevenTvEvent {
        override val actor: String? get() = null
    }

    /** Someone started or stopped wearing the [cosmetic] [refId]. */
    data class EntitlementChanged(
        val twitchUserId: String,
        val refId: String,
        val worn: Boolean,
        val cosmetic: Cosmetic = Cosmetic.Badge,
    ) : SevenTvEvent {
        override val actor: String? get() = null
    }

    /** The cosmetics Chatter draws. */
    enum class Cosmetic { Badge, Paint }
}

/**
 * An EventAPI subscription: event type and condition. Emote sets and users are named by their 7TV
 * id; cosmetics are subscribed per Twitch channel, like Chatterino does.
 */
data class SevenTvSubscription(val type: String, val condition: Map<String, String>) {
    companion object {
        fun ofObject(type: String, id: String) = SevenTvSubscription(type, mapOf("object_id" to id))

        fun ofChannel(type: String, twitchChannelId: String) = SevenTvSubscription(
            type,
            mapOf("ctx" to "channel", "platform" to "TWITCH", "id" to twitchChannelId),
        )
    }
}

/**
 * Connection to wss://events.7tv.io/v3. [subscriptions] are sent again after every reconnect.
 *
 * The server sends heartbeats. The socket has no read timeout, since a channel can go hours without
 * an emote change, so a silent network change would leave a dead socket that never fails.
 * [startWatchdog] reconnects after two missed heartbeats, like the chat's watchdog.
 */
class SevenTvEventClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var socket: WebSocket? = null
    private var wanted = false
    private var ready = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var subscriptions: Set<SevenTvSubscription> = emptySet()

    /** The heartbeat interval from the last hello; a default until then. */
    @Volatile private var heartbeatMs = DEFAULT_HEARTBEAT_MS
    @Volatile private var lastActivity = 0L

    /** Retrying without a network cannot succeed; see [setNetwork]. */
    private var networkUp = true

    /** Whether a connection since [start] got a hello, which makes the next one a reconnect. */
    private var helloSinceStart = false

    private val _events = MutableSharedFlow<SevenTvEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SevenTvEvent> = _events

    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * A lost connection came back. Changes in between were not pushed, so the emote sets should be
     * reloaded.
     */
    val reconnected: SharedFlow<Unit> = _reconnected

    fun start() = synchronized(lock) {
        wanted = true
        helloSinceStart = false
        if (socket == null && networkUp && subscriptions.isNotEmpty()) open()
    }

    fun stop() = synchronized(lock) {
        wanted = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        socket?.close(1000, null)
        socket = null
        ready = false
    }

    /**
     * Network changes, as the chat connection gets them. Losing it stops retries; when it comes
     * back the connection starts over, since a socket from the old network is likely dead.
     */
    fun setNetwork(up: Boolean) = synchronized(lock) {
        val cameBack = up && !networkUp
        networkUp = up
        if (!wanted) return@synchronized
        if (!up) {
            reconnectJob?.cancel()
            watchdogJob?.cancel()
            socket?.cancel()
            socket = null
            ready = false
        } else if (cameBack) {
            attempt = 0
            reconnectJob?.cancel()
            socket?.cancel()
            socket = null
            if (subscriptions.isNotEmpty()) open()
        }
    }

    /** Replaces the subscriptions; only the difference is sent. */
    fun setSubscriptions(new: Set<SevenTvSubscription>) = synchronized(lock) {
        val old = subscriptions
        subscriptions = new
        val ws = socket
        if (ws != null && ready) {
            (old - new).forEach { ws.send(subscriptionMessage(36, it)) }
            (new - old).forEach { ws.send(subscriptionMessage(35, it)) }
        } else if (wanted && networkUp && socket == null && new.isNotEmpty()) {
            open()
        }
    }

    private fun open() {
        ready = false
        lastActivity = System.currentTimeMillis()
        val ws = http.newWebSocket(Request.Builder().url("wss://events.7tv.io/v3").build(), Listener())
        socket = ws
        startWatchdog(ws)
    }

    /**
     * Reconnects when [ws] has been silent for two heartbeat intervals (a default before the hello,
     * which also covers a socket that never greets us). Sleeps until the silence could be long
     * enough.
     */
    private fun startWatchdog(ws: WebSocket) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                val limit = 2 * heartbeatMs
                val silence = System.currentTimeMillis() - lastActivity
                if (silence >= limit) {
                    Log.i(TAG, "No heartbeat for ${silence / 1000}s, reconnecting")
                    synchronized(lock) { if (socket === ws) scheduleReconnect() }
                    break
                }
                delay(limit - silence)
            }
        }
    }

    private fun scheduleReconnect() = synchronized(lock) {
        socket?.cancel()
        socket = null
        ready = false
        watchdogJob?.cancel()
        // setNetwork reconnects once the network is back.
        if (!wanted || !networkUp || reconnectJob?.isActive == true) return@synchronized
        val delayMs = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L) + Random.nextLong(0, 1000)
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) { if (wanted && networkUp && socket == null && subscriptions.isNotEmpty()) open() }
        }
    }

    private inner class Listener : WebSocketListener() {
        private fun isCurrent(ws: WebSocket) = synchronized(lock) { socket === ws }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            lastActivity = System.currentTimeMillis()
            val msg = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (msg["op"]?.jsonPrimitive?.int) {
                1 -> synchronized(lock) { // hello: (re)send all subscriptions
                    heartbeatMs = heartbeatInterval(msg)
                    ready = true
                    attempt = 0
                    subscriptions.forEach { webSocket.send(subscriptionMessage(35, it)) }
                    if (helloSinceStart) _reconnected.tryEmit(Unit)
                    helloSinceStart = true
                }
                0 -> msg["d"]?.jsonObject?.let { parseDispatch(it) }?.let { _events.tryEmit(it) }
                4, 7 -> { // reconnect requested / end of stream
                    webSocket.close(1000, null)
                    scheduleReconnect()
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (isCurrent(webSocket)) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "7TV events failed: ${t.message}")
            if (isCurrent(webSocket)) scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "SevenTvEvents"

        /** 7TV's interval so far, used until the hello says otherwise. */
        private const val DEFAULT_HEARTBEAT_MS = 30_000L

        /**
         * The heartbeat interval from a hello (op 1) in milliseconds, clamped so 0 cannot make the
         * watchdog spin and a huge value cannot disable it. Internal for tests.
         */
        internal fun heartbeatInterval(hello: JsonObject): Long =
            (hello["d"] as? JsonObject)?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull
                ?.coerceIn(5_000L, 5 * 60_000L) ?: DEFAULT_HEARTBEAT_MS

        /** The kinds 7TV gives its cosmetics, as Chatter draws them. */
        private val COSMETICS = mapOf("BADGE" to SevenTvEvent.Cosmetic.Badge, "PAINT" to SevenTvEvent.Cosmetic.Paint)

        private fun subscriptionMessage(op: Int, subscription: SevenTvSubscription) = buildJsonObject {
            put("op", op)
            putJsonObject("d") {
                put("type", subscription.type)
                putJsonObject("condition") { subscription.condition.forEach { (k, v) -> put(k, v) } }
            }
        }.toString()

        /** Parses the `d` object of a dispatch (op 0). Internal for tests. */
        internal fun parseDispatch(d: JsonObject): SevenTvEvent? {
            val body = d["body"]?.jsonObject ?: return null
            val type = d["type"]?.jsonPrimitive?.contentOrNull
            // Cosmetics are about a person, not an emote set, and have no id of their own.
            when (type) {
                "cosmetic.create" -> return cosmetic(body)
                "entitlement.create" -> return entitlement(body, worn = true)
                "entitlement.delete" -> return entitlement(body, worn = false)
            }
            val id = body["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val actor = body["actor"]?.let { it as? JsonObject }?.get("display_name")?.jsonPrimitive?.contentOrNull
            return when (type) {
                "emote_set.update" -> {
                    fun changes(key: String) = body[key]?.let { it as? JsonArray }.orEmpty()
                        .map { it.jsonObject }
                        .filter { it["key"]?.jsonPrimitive?.contentOrNull == "emotes" }
                    val added = changes("pushed").mapNotNull { emote(it["value"]) }
                    val removed = changes("pulled").mapNotNull { emote(it["old_value"]) }
                    val renamed = changes("updated").mapNotNull { c ->
                        val old = emote(c["old_value"]) ?: return@mapNotNull null
                        val new = emote(c["value"]) ?: return@mapNotNull null
                        (old to new).takeIf { old.name != new.name }
                    }
                    SevenTvEvent.EmoteSetUpdate(id, actor, added, removed, renamed)
                        .takeIf { added.isNotEmpty() || removed.isNotEmpty() || renamed.isNotEmpty() }
                }
                "user.update" -> {
                    // The active set lives in updated[key=connections].value[key=emote_set].
                    val setChange = body["updated"]?.let { it as? JsonArray }.orEmpty()
                        .map { it.jsonObject }
                        .filter { it["key"]?.jsonPrimitive?.contentOrNull == "connections" }
                        .flatMap { it["value"]?.let { v -> v as? JsonArray }.orEmpty() }
                        .map { it.jsonObject }
                        .firstOrNull { it["key"]?.jsonPrimitive?.contentOrNull == "emote_set" }
                        ?: return null
                    val newSet = setChange["value"]?.let { it as? JsonObject }?.get("id")?.jsonPrimitive?.contentOrNull
                    SevenTvEvent.ActiveSetChanged(id, actor, newSet)
                }
                else -> null
            }
        }

        /** `cosmetic.create`: a badge's picture and name, or how a paint is drawn. */
        private fun cosmetic(body: JsonObject): SevenTvEvent? {
            val obj = body["object"]?.let { it as? JsonObject } ?: return null
            val data = obj["data"]?.let { it as? JsonObject } ?: return null
            return when (COSMETICS[obj["kind"]?.jsonPrimitive?.contentOrNull]) {
                SevenTvEvent.Cosmetic.Badge -> badge(data)
                SevenTvEvent.Cosmetic.Paint -> NamePaint.parse(data)?.let { SevenTvEvent.PaintCreated(it) }
                null -> null
            }
        }

        private fun badge(data: JsonObject): SevenTvEvent? {
            val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val name = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tooltip = data["tooltip"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: name
            return SevenTvEvent.BadgeCreated(id, name, tooltip)
        }

        /**
         * `entitlement.create` / `.delete`: who wears a cosmetic. 7TV lists the wearer's accounts
         * on every platform; only the Twitch one matters here.
         */
        private fun entitlement(body: JsonObject, worn: Boolean): SevenTvEvent? {
            val obj = body["object"]?.let { it as? JsonObject } ?: return null
            val cosmetic = COSMETICS[obj["kind"]?.jsonPrimitive?.contentOrNull] ?: return null
            val refId = obj["ref_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val twitchId = obj["user"]?.let { it as? JsonObject }
                ?.get("connections")?.let { it as? JsonArray }.orEmpty()
                .map { it.jsonObject }
                .firstOrNull { it["platform"]?.jsonPrimitive?.contentOrNull == "TWITCH" }
                ?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
            return SevenTvEvent.EntitlementChanged(twitchId, refId, worn, cosmetic)
        }

        private fun emote(e: JsonElement?): SevenTvActiveEmote? =
            (e as? JsonObject)?.let { runCatching { AppJson.decodeFromJsonElement<SevenTvActiveEmote>(it) }.getOrNull() }
    }
}
