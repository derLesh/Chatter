package dev.chatter.app.emotes

import android.util.Log
import dev.chatter.app.net.AppJson
import dev.chatter.app.net.SevenTvActiveEmote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/** A change to a 7TV emote set or user, pushed by the 7TV EventAPI. */
sealed interface SevenTvEvent {
    val actor: String?

    data class EmoteSetUpdate(
        val setId: String,
        override val actor: String?,
        val added: List<SevenTvActiveEmote>,
        val removed: List<SevenTvActiveEmote>,
        /** Old to new version of renamed emotes. */
        val renamed: List<Pair<SevenTvActiveEmote, SevenTvActiveEmote>>,
    ) : SevenTvEvent

    /** The user (channel) switched to a different emote set. */
    data class ActiveSetChanged(val userId: String, override val actor: String?, val newSetId: String?) : SevenTvEvent

    /**
     * A badge 7TV has just described, for somebody in one of the channels we listen to. It says
     * what the badge is; an [EntitlementChanged] says who wears it.
     */
    data class BadgeCreated(val id: String, val name: String, val tooltip: String) : SevenTvEvent {
        override val actor: String? get() = null
    }

    /** Somebody started or stopped wearing the cosmetic [refId]. */
    data class EntitlementChanged(val twitchUserId: String, val refId: String, val worn: Boolean) : SevenTvEvent {
        override val actor: String? get() = null
    }
}

/**
 * One thing to listen to at the EventAPI: an event type and the condition that narrows it down.
 *
 * Emote sets and users are named by their 7TV id; cosmetics are asked for per Twitch channel,
 * which is how 7TV hands out the badges of the people in it (the same way Chatterino does it).
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
 * Connection to wss://events.7tv.io/v3. Subscriptions are kept in [subscriptions] and sent again
 * after every reconnect. The server sends its own heartbeats; we only listen.
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
    private var subscriptions: Set<SevenTvSubscription> = emptySet()

    private val _events = MutableSharedFlow<SevenTvEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<SevenTvEvent> = _events

    fun start() = synchronized(lock) {
        wanted = true
        if (socket == null && subscriptions.isNotEmpty()) open()
    }

    fun stop() = synchronized(lock) {
        wanted = false
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        ready = false
    }

    /** Replaces the subscription set; only the difference is sent to the server. */
    fun setSubscriptions(new: Set<SevenTvSubscription>) = synchronized(lock) {
        val old = subscriptions
        subscriptions = new
        val ws = socket
        if (ws != null && ready) {
            (old - new).forEach { ws.send(subscriptionMessage(36, it)) }
            (new - old).forEach { ws.send(subscriptionMessage(35, it)) }
        } else if (wanted && socket == null && new.isNotEmpty()) {
            open()
        }
    }

    private fun open() {
        ready = false
        socket = http.newWebSocket(Request.Builder().url("wss://events.7tv.io/v3").build(), Listener())
    }

    private fun scheduleReconnect() = synchronized(lock) {
        socket = null
        ready = false
        if (!wanted || reconnectJob?.isActive == true) return@synchronized
        val delayMs = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L) + Random.nextLong(0, 1000)
        attempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) { if (wanted && socket == null && subscriptions.isNotEmpty()) open() }
        }
    }

    private inner class Listener : WebSocketListener() {
        private fun isCurrent(ws: WebSocket) = synchronized(lock) { socket === ws }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            val msg = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (msg["op"]?.jsonPrimitive?.int) {
                1 -> synchronized(lock) { // hello: (re)send all subscriptions
                    ready = true
                    attempt = 0
                    subscriptions.forEach { webSocket.send(subscriptionMessage(35, it)) }
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

        /** The one kind of cosmetic Chatter shows; 7TV also hands out paints. */
        private const val BADGE = "BADGE"

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
            // Cosmetics are about a person, not about an emote set, and carry no id of their own.
            when (type) {
                "cosmetic.create" -> return badge(body)
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

        /** `cosmetic.create` for a badge: what it looks like and what it is called. */
        private fun badge(body: JsonObject): SevenTvEvent? {
            val obj = body["object"]?.let { it as? JsonObject } ?: return null
            if (obj["kind"]?.jsonPrimitive?.contentOrNull != BADGE) return null
            val data = obj["data"]?.let { it as? JsonObject } ?: return null
            val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val name = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tooltip = data["tooltip"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: name
            return SevenTvEvent.BadgeCreated(id, name, tooltip)
        }

        /**
         * `entitlement.create` / `.delete`: who wears a cosmetic. 7TV names the wearer by their
         * accounts on every platform, of which only the Twitch one says anything about a chatter.
         */
        private fun entitlement(body: JsonObject, worn: Boolean): SevenTvEvent? {
            val obj = body["object"]?.let { it as? JsonObject } ?: return null
            if (obj["kind"]?.jsonPrimitive?.contentOrNull != BADGE) return null
            val refId = obj["ref_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val twitchId = obj["user"]?.let { it as? JsonObject }
                ?.get("connections")?.let { it as? JsonArray }.orEmpty()
                .map { it.jsonObject }
                .firstOrNull { it["platform"]?.jsonPrimitive?.contentOrNull == "TWITCH" }
                ?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
            return SevenTvEvent.EntitlementChanged(twitchId, refId, worn)
        }

        private fun emote(e: JsonElement?): SevenTvActiveEmote? =
            (e as? JsonObject)?.let { runCatching { AppJson.decodeFromJsonElement<SevenTvActiveEmote>(it) }.getOrNull() }
    }
}
