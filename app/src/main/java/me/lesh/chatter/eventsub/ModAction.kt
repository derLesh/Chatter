package me.lesh.chatter.eventsub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Someone a mod acted on. */
data class ModTarget(val login: String, val name: String)

/**
 * What a mod did, from `channel.moderate`. Only what the chat shows: deleted messages are struck
 * out by IRC already, and Shared Chat partners' actions belong to their own chat.
 */
sealed interface ModAction {
    data class Ban(val target: ModTarget, val reason: String?) : ModAction
    data class Timeout(val target: ModTarget, val seconds: Int, val reason: String?) : ModAction
    data class Unban(val target: ModTarget) : ModAction
    data class Untimeout(val target: ModTarget) : ModAction
    data object Clear : ModAction
    data class Warn(val target: ModTarget, val reason: String?) : ModAction

    /** [value] is the slow mode's seconds or how many minutes one must follow; 0 for none. */
    data class Mode(val mode: ChatModeKind, val on: Boolean, val value: Int = 0) : ModAction
    data class Role(val target: ModTarget, val role: RoleKind, val added: Boolean) : ModAction
    data class Raid(val target: ModTarget) : ModAction
    data class Unraid(val target: ModTarget) : ModAction
    data class UnbanRequest(val target: ModTarget, val approved: Boolean) : ModAction

    enum class ChatModeKind { EmoteOnly, Followers, Slow, Subscribers, Unique }
    enum class RoleKind { Moderator, Vip }

    companion object {
        /** The action of a `channel.moderate` v2 event sent at [sentAt]; null for one not shown. */
        internal fun parse(e: JsonObject, sentAt: Long): ModAction? {
            val action = e.str("action") ?: return null
            fun target() = e.obj(action)?.let(::target)
            fun reason() = e.obj(action)?.str("reason")?.ifBlank { null }
            fun on(mode: ChatModeKind) = Mode(mode, on = true)
            fun off(mode: ChatModeKind) = Mode(mode, on = false)
            return when (action) {
                "ban" -> target()?.let { Ban(it, reason()) }
                "timeout" -> {
                    val details = e.obj("timeout") ?: return null
                    val expiresAt = EventSubFrame.time(details.str("expires_at")) ?: return null
                    // Twitch sends when it ends, not for how long; it was set when this was sent.
                    val seconds = ((expiresAt - sentAt + 500) / 1000).toInt().coerceAtLeast(1)
                    target(details)?.let { Timeout(it, seconds, reason()) }
                }
                "unban" -> target()?.let(::Unban)
                "untimeout" -> target()?.let(::Untimeout)
                "clear" -> Clear
                "warn" -> target()?.let { Warn(it, reason()) }
                "emoteonly" -> on(ChatModeKind.EmoteOnly)
                "emoteonlyoff" -> off(ChatModeKind.EmoteOnly)
                "followers" -> Mode(ChatModeKind.Followers, true, e.obj("followers").int("follow_duration_minutes"))
                "followersoff" -> off(ChatModeKind.Followers)
                "slow" -> Mode(ChatModeKind.Slow, true, e.obj("slow").int("wait_time_seconds"))
                "slowoff" -> off(ChatModeKind.Slow)
                "subscribers" -> on(ChatModeKind.Subscribers)
                "subscribersoff" -> off(ChatModeKind.Subscribers)
                "uniquechat" -> on(ChatModeKind.Unique)
                "uniquechatoff" -> off(ChatModeKind.Unique)
                "mod" -> target()?.let { Role(it, RoleKind.Moderator, added = true) }
                "unmod" -> target()?.let { Role(it, RoleKind.Moderator, added = false) }
                "vip" -> target()?.let { Role(it, RoleKind.Vip, added = true) }
                "unvip" -> target()?.let { Role(it, RoleKind.Vip, added = false) }
                "raid" -> target()?.let(::Raid)
                "unraid" -> target()?.let(::Unraid)
                "approve_unban_request", "deny_unban_request" -> e.obj("unban_request")?.let(::target)
                    ?.let { UnbanRequest(it, approved = action == "approve_unban_request") }
                else -> null
            }
        }

        private fun target(o: JsonObject): ModTarget? {
            val login = o.str("user_login") ?: return null
            return ModTarget(login, o.str("user_name")?.ifEmpty { null } ?: login)
        }

        private fun JsonObject?.int(key: String): Int = this?.get(key)?.jsonPrimitive?.intOrNull ?: 0
    }
}
