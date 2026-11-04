package me.lesh.chatter.chat

import android.content.Context
import me.lesh.chatter.R
import me.lesh.chatter.eventsub.ModAction
import me.lesh.chatter.eventsub.ModAction.ChatModeKind
import me.lesh.chatter.eventsub.ModAction.RoleKind

/** [ChatNotices], [ModLogNotices] and [OwnHoldNotices] in the phone's language. */
class AppChatNotices(private val context: Context) : ChatNotices, ModLogNotices, OwnHoldNotices {
    override fun chatCleared(): String = context.getString(R.string.chat_cleared)

    override fun timeout(name: String, seconds: Int): String =
        context.resources.getQuantityString(R.plurals.chat_timeout, seconds, name, seconds)

    override fun ban(name: String): String = context.getString(R.string.chat_ban, name)

    override fun allowed(): String = str(R.string.own_held_allowed)

    override fun denied(): String = str(R.string.own_held_denied)

    override fun describe(moderator: String, action: ModAction): String? = when (action) {
        is ModAction.Ban -> withReason(str(R.string.mod_ban, moderator, action.target.name), action.reason)
        is ModAction.Timeout -> withReason(
            plural(R.plurals.mod_timeout, action.seconds, moderator, action.target.name, action.seconds),
            action.reason,
        )
        is ModAction.Unban -> str(R.string.mod_unban, moderator, action.target.name)
        is ModAction.Untimeout -> str(R.string.mod_untimeout, moderator, action.target.name)
        ModAction.Clear -> str(R.string.mod_clear, moderator)
        is ModAction.Warn -> withReason(str(R.string.mod_warn, moderator, action.target.name), action.reason)
        is ModAction.Mode -> mode(moderator, action)
        is ModAction.Role -> str(
            when (action.role) {
                RoleKind.Moderator -> if (action.added) R.string.mod_mod else R.string.mod_unmod
                RoleKind.Vip -> if (action.added) R.string.mod_vip else R.string.mod_unvip
            },
            moderator, action.target.name,
        )
        is ModAction.Raid -> str(R.string.mod_raid, moderator, action.target.name)
        is ModAction.Unraid -> str(R.string.mod_unraid, moderator, action.target.name)
        is ModAction.UnbanRequest ->
            str(if (action.approved) R.string.mod_unban_request_approved else R.string.mod_unban_request_denied, moderator, action.target.name)
    }

    private fun mode(moderator: String, action: ModAction.Mode): String = when (action.mode) {
        ChatModeKind.Slow -> if (action.on) plural(R.plurals.mod_slow_on, action.value, moderator, action.value) else str(R.string.mod_slow_off, moderator)
        ChatModeKind.Followers -> when {
            !action.on -> str(R.string.mod_followers_off, moderator)
            action.value > 0 -> plural(R.plurals.mod_followers_on_time, action.value, moderator, action.value)
            else -> str(R.string.mod_followers_on, moderator)
        }
        ChatModeKind.Subscribers -> str(if (action.on) R.string.mod_subs_on else R.string.mod_subs_off, moderator)
        ChatModeKind.EmoteOnly -> str(if (action.on) R.string.mod_emotes_on else R.string.mod_emotes_off, moderator)
        ChatModeKind.Unique -> str(if (action.on) R.string.mod_unique_on else R.string.mod_unique_off, moderator)
    }

    private fun withReason(text: String, reason: String?) =
        if (reason.isNullOrBlank()) text else str(R.string.mod_reason, text, reason)

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)

    private fun plural(res: Int, count: Int, vararg args: Any) = context.resources.getQuantityString(res, count, *args)
}
