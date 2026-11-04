package me.lesh.chatter.eventsub

import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.HelixFragment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** Something EventSub reported, as the chat uses it. */
sealed interface EventSubEvent {
    /** The Twitch id of the channel it happened in. */
    val channelId: String

    /** AutoMod held a message back until a mod allows or denies it. */
    data class AutoModHeld(
        override val channelId: String,
        val messageId: String,
        val userId: String,
        val login: String,
        val displayName: String,
        val text: String,
        val fragments: List<HelixFragment>,
        val reason: HoldReason,
        /** Epoch millis. */
        val heldAt: Long,
    ) : EventSubEvent

    /** A held message was allowed, denied or left to expire; [moderator] is who decided. */
    data class AutoModResolved(
        override val channelId: String,
        val messageId: String,
        val status: HoldStatus,
        val moderator: String?,
    ) : EventSubEvent

    /** AutoMod held a message the user wrote; a mod decides on it. */
    data class OwnMessageHeld(override val channelId: String, val messageId: String, val text: String) : EventSubEvent

    /** A mod decided on the user's held message, or it expired. */
    data class OwnMessageResolved(override val channelId: String, val messageId: String, val status: HoldStatus) : EventSubEvent

    /** A mod did [action]; [moderator] is their display name. */
    data class Moderation(
        override val channelId: String,
        val moderator: String,
        val action: ModAction,
    ) : EventSubEvent
}

/** Why AutoMod held a message. */
sealed interface HoldReason {
    /** Twitch's own filter; [level] runs from 1 (mild) to 4. */
    data class AutoMod(val category: String, val level: Int) : HoldReason

    /** A word on the channel's list of blocked terms. */
    data object BlockedTerm : HoldReason
}

enum class HoldStatus { Approved, Denied, Expired, Invalid }

/** One frame from the EventSub socket. */
internal sealed interface EventSubFrame {
    data class Welcome(val sessionId: String, val keepaliveSeconds: Int?) : EventSubFrame

    data object Keepalive : EventSubFrame

    /** Twitch moves the session to [url]; the subscriptions move with it. */
    data class Reconnect(val url: String) : EventSubFrame

    /** [event] is null for a type Chatter does not read. */
    data class Notification(val messageId: String, val event: EventSubEvent?) : EventSubFrame

    /** Twitch ended a subscription, e.g. because the user is no longer a mod there. */
    data class Revocation(val subscriptionId: String) : EventSubFrame

    companion object {
        /** Null for anything that is not a frame Twitch documents. */
        fun parse(text: String): EventSubFrame? {
            val root = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val metadata = root.obj("metadata") ?: return null
            val payload = root.obj("payload") ?: return null
            return when (metadata.str("message_type")) {
                "session_welcome" -> payload.obj("session")?.let { session ->
                    session.str("id")?.let { Welcome(it, session["keepalive_timeout_seconds"]?.jsonPrimitive?.intOrNull) }
                }
                "session_keepalive" -> Keepalive
                "session_reconnect" -> payload.obj("session")?.str("reconnect_url")?.let(::Reconnect)
                "notification" -> {
                    val id = metadata.str("message_id") ?: return null
                    val event = payload.obj("event")
                    val sentAt = time(metadata.str("message_timestamp"))
                    Notification(id, event?.let { parseEvent(metadata.str("subscription_type"), it, sentAt) })
                }
                "revocation" -> payload.obj("subscription")?.str("id")?.let(::Revocation)
                else -> null
            }
        }

        /** The event of a notification of [type], sent at [sentAt]; internal for tests. */
        internal fun parseEvent(type: String?, e: JsonObject, sentAt: Long? = null): EventSubEvent? {
            val channelId = e.str("broadcaster_user_id") ?: return null
            return when (type) {
                "automod.message.hold" -> autoModHeld(channelId, e)
                "automod.message.update" -> {
                    val messageId = e.str("message_id") ?: return null
                    val status = holdStatus(e.str("status")) ?: return null
                    EventSubEvent.AutoModResolved(channelId, messageId, status, e.str("moderator_user_name"))
                }
                "channel.chat.user_message_hold" -> {
                    val messageId = e.str("message_id") ?: return null
                    EventSubEvent.OwnMessageHeld(channelId, messageId, e.obj("message")?.str("text").orEmpty())
                }
                "channel.chat.user_message_update" -> {
                    val messageId = e.str("message_id") ?: return null
                    val status = holdStatus(e.str("status")) ?: return null
                    EventSubEvent.OwnMessageResolved(channelId, messageId, status)
                }
                "channel.moderate" -> ModAction.parse(e, sentAt ?: System.currentTimeMillis())?.let { action ->
                    val moderator = e.str("moderator_user_name")?.ifEmpty { null } ?: e.str("moderator_user_login") ?: return null
                    EventSubEvent.Moderation(channelId, moderator, action)
                }
                else -> null
            }
        }

        private fun autoModHeld(channelId: String, e: JsonObject): EventSubEvent.AutoModHeld? {
            val message = e.obj("message") ?: return null
            val reason = when (e.str("reason")) {
                "blocked_term" -> HoldReason.BlockedTerm
                else -> e.obj("automod")?.let {
                    HoldReason.AutoMod(it.str("category").orEmpty(), it["level"]?.jsonPrimitive?.intOrNull ?: 0)
                } ?: return null
            }
            val login = e.str("user_login") ?: return null
            return EventSubEvent.AutoModHeld(
                channelId = channelId,
                messageId = e.str("message_id") ?: return null,
                userId = e.str("user_id").orEmpty(),
                login = login,
                displayName = e.str("user_name")?.ifEmpty { null } ?: login,
                text = message.str("text").orEmpty(),
                fragments = fragments(message),
                reason = reason,
                heldAt = time(e.str("held_at")) ?: System.currentTimeMillis(),
            )
        }

        private fun holdStatus(status: String?): HoldStatus? = when (status) {
            "approved" -> HoldStatus.Approved
            "denied" -> HoldStatus.Denied
            "expired" -> HoldStatus.Expired
            "invalid" -> HoldStatus.Invalid
            else -> null
        }

        internal fun fragments(message: JsonObject): List<HelixFragment> =
            (message["fragments"] as? JsonArray)?.mapNotNull {
                runCatching { AppJson.decodeFromJsonElement<HelixFragment>(it) }.getOrNull()
            }.orEmpty()

        internal fun time(rfc3339: String?): Long? =
            rfc3339?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
