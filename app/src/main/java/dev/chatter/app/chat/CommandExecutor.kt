package dev.chatter.app.chat

import android.content.Context
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HttpException
import kotlinx.coroutines.CancellationException

/** Runs [ChatCommand]s against the Helix API and returns a message to show in the chat. */
class CommandExecutor(
    private val context: Context,
    private val helix: HelixApi,
    private val auth: AuthRepository,
) {
    suspend fun execute(command: ChatCommand, channelId: String?): String {
        val me = auth.account?.userId ?: return str(R.string.error_not_connected)
        if (channelId == null) return str(R.string.cmd_error_generic, "unknown channel")
        return try {
            run(command, channelId, me)
        } catch (e: HttpException) {
            when {
                e.code == 401 && e.apiMessage?.contains("scope", ignoreCase = true) == true -> str(R.string.cmd_error_scope)
                e.code == 401 || e.code == 403 -> str(R.string.cmd_error_permission, e.apiMessage ?: "")
                else -> str(R.string.cmd_error_generic, e.apiMessage ?: "HTTP ${e.code}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            str(R.string.cmd_error_generic, e.message ?: e.toString())
        }
    }

    private suspend fun run(command: ChatCommand, channel: String, me: String): String = when (command) {
        is ChatCommand.Ban -> {
            helix.ban(channel, me, userId(command.user), null, command.reason)
            str(R.string.cmd_banned, command.user)
        }
        is ChatCommand.Timeout -> {
            helix.ban(channel, me, userId(command.user), command.seconds, command.reason)
            str(R.string.cmd_timed_out, command.user, formatDuration(command.seconds))
        }
        is ChatCommand.Unban -> {
            helix.unban(channel, me, userId(command.user))
            str(R.string.cmd_unbanned, command.user)
        }
        is ChatCommand.Delete -> {
            helix.deleteMessages(channel, me, command.messageId)
            str(R.string.cmd_deleted)
        }
        ChatCommand.Clear -> {
            helix.deleteMessages(channel, me, null)
            str(R.string.cmd_cleared)
        }
        is ChatCommand.Settings -> {
            helix.updateChatSettings(channel, me, command.settings)
            str(R.string.cmd_settings_updated)
        }
        is ChatCommand.Mod -> {
            helix.setModerator(channel, userId(command.user), command.add)
            str(if (command.add) R.string.cmd_modded else R.string.cmd_unmodded, command.user)
        }
        is ChatCommand.Vip -> {
            helix.setVip(channel, userId(command.user), command.add)
            str(if (command.add) R.string.cmd_vipped else R.string.cmd_unvipped, command.user)
        }
        is ChatCommand.Announce -> {
            helix.announce(channel, me, command.message)
            str(R.string.cmd_announced)
        }
        is ChatCommand.Shoutout -> {
            helix.shoutout(channel, me, userId(command.user))
            str(R.string.cmd_shoutout, command.user)
        }
        is ChatCommand.Raid -> {
            helix.startRaid(channel, userId(command.user))
            str(R.string.cmd_raid, command.user)
        }
        ChatCommand.Unraid -> {
            helix.cancelRaid(channel)
            str(R.string.cmd_unraid)
        }
        is ChatCommand.Color -> {
            helix.setChatColor(me, command.color)
            str(R.string.cmd_color, command.color)
        }
        is ChatCommand.Usage -> str(R.string.cmd_usage, command.usage)
        is ChatCommand.Unknown -> str(R.string.error_unsupported_command, command.name)
    }

    private suspend fun userId(login: String): String =
        helix.users(listOf(login)).firstOrNull()?.id ?: throw IllegalArgumentException(str(R.string.cmd_error_no_user, login))

    private fun formatDuration(seconds: Int): String = when {
        seconds % 86_400 == 0 -> "${seconds / 86_400}d"
        seconds % 3600 == 0 -> "${seconds / 3600}h"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }

    private fun str(res: Int, vararg args: Any) = context.getString(res, *args)
}
