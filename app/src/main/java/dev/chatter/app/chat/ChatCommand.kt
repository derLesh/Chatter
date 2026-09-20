package dev.chatter.app.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A slash command typed into the input. Executed via the Helix API (see CommandExecutor). */
sealed interface ChatCommand {
    data class Ban(val user: String, val reason: String?) : ChatCommand
    data class Unban(val user: String) : ChatCommand
    data class Timeout(val user: String, val seconds: Int, val reason: String?) : ChatCommand
    data class Delete(val messageId: String) : ChatCommand
    data object Clear : ChatCommand
    /** Chat settings change like slow mode; [settings] is the Helix PATCH body. */
    data class Settings(val settings: JsonObject) : ChatCommand
    data class Mod(val user: String, val add: Boolean) : ChatCommand
    data class Vip(val user: String, val add: Boolean) : ChatCommand
    data class Announce(val message: String) : ChatCommand
    data class Shoutout(val user: String) : ChatCommand
    data class Raid(val user: String) : ChatCommand
    data object Unraid : ChatCommand
    data class Color(val color: String) : ChatCommand
    /** A whisper, the one command that goes to a person instead of into a channel. */
    data class Whisper(val user: String, val message: String) : ChatCommand

    /** Known command with wrong arguments; [usage] shows the correct syntax. */
    data class Usage(val usage: String) : ChatCommand
    data class Unknown(val name: String) : ChatCommand
}

object CommandParser {
    /** Commands with their syntax, for autocomplete and usage hints. */
    val COMMANDS: Map<String, String> = linkedMapOf(
        "me" to "/me <message>",
        "w" to "/w <user> <message>",
        "whisper" to "/whisper <user> <message>",
        "ban" to "/ban <user> [reason]",
        "unban" to "/unban <user>",
        "timeout" to "/timeout <user> [duration] [reason]",
        "untimeout" to "/untimeout <user>",
        "clear" to "/clear",
        "slow" to "/slow [seconds]",
        "slowoff" to "/slowoff",
        "followers" to "/followers [duration]",
        "followersoff" to "/followersoff",
        "subscribers" to "/subscribers",
        "subscribersoff" to "/subscribersoff",
        "emoteonly" to "/emoteonly",
        "emoteonlyoff" to "/emoteonlyoff",
        "uniquechat" to "/uniquechat",
        "uniquechatoff" to "/uniquechatoff",
        "mod" to "/mod <user>",
        "unmod" to "/unmod <user>",
        "vip" to "/vip <user>",
        "unvip" to "/unvip <user>",
        "announce" to "/announce <message>",
        "shoutout" to "/shoutout <user>",
        "raid" to "/raid <channel>",
        "unraid" to "/unraid",
        "color" to "/color <color>",
    )

    /** Returns null for normal messages and `/me` (which is sent as a chat message). */
    fun parse(input: String): ChatCommand? {
        val text = input.trim()
        if (!text.startsWith("/") || text.startsWith("/me ")) return null
        val parts = text.substring(1).split(Regex("\\s+"), limit = 2)
        val name = parts[0].lowercase()
        val rest = parts.getOrNull(1)?.trim().orEmpty()
        val args = if (rest.isEmpty()) emptyList() else rest.split(Regex("\\s+"))
        val user = args.firstOrNull()?.removePrefix("@")?.lowercase()
        fun usage() = ChatCommand.Usage(COMMANDS.getValue(name))
        fun afterFirst(n: Int) = args.drop(n).joinToString(" ").ifBlank { null }

        return when (name) {
            "ban" -> user?.let { ChatCommand.Ban(it, afterFirst(1)) } ?: usage()
            "unban", "untimeout" -> user?.let { ChatCommand.Unban(it) } ?: usage()
            "timeout" -> {
                if (user == null) return usage()
                val seconds = args.getOrNull(1)?.let { parseDuration(it) }
                when {
                    args.size >= 2 && seconds == null -> usage()
                    else -> ChatCommand.Timeout(user, (seconds ?: 600).coerceIn(1, 1_209_600), afterFirst(2))
                }
            }
            "w", "whisper" -> {
                val message = afterFirst(1)
                if (user == null || message == null) usage() else ChatCommand.Whisper(user, message)
            }
            "clear" -> ChatCommand.Clear
            "slow" -> {
                val seconds = args.firstOrNull()?.let { parseDuration(it) ?: return usage() } ?: 30
                ChatCommand.Settings(buildJsonObject {
                    put("slow_mode", true)
                    put("slow_mode_wait_time", seconds.coerceIn(3, 120))
                })
            }
            "slowoff" -> ChatCommand.Settings(buildJsonObject { put("slow_mode", false) })
            "followers" -> {
                val minutes = args.firstOrNull()?.let { parseDuration(it, defaultUnit = 60) ?: return usage() }?.div(60) ?: 0
                ChatCommand.Settings(buildJsonObject {
                    put("follower_mode", true)
                    put("follower_mode_duration", minutes.coerceIn(0, 129_600))
                })
            }
            "followersoff" -> ChatCommand.Settings(buildJsonObject { put("follower_mode", false) })
            "subscribers" -> ChatCommand.Settings(buildJsonObject { put("subscriber_mode", true) })
            "subscribersoff" -> ChatCommand.Settings(buildJsonObject { put("subscriber_mode", false) })
            "emoteonly" -> ChatCommand.Settings(buildJsonObject { put("emote_mode", true) })
            "emoteonlyoff" -> ChatCommand.Settings(buildJsonObject { put("emote_mode", false) })
            "uniquechat" -> ChatCommand.Settings(buildJsonObject { put("unique_chat_mode", true) })
            "uniquechatoff" -> ChatCommand.Settings(buildJsonObject { put("unique_chat_mode", false) })
            "mod", "unmod" -> user?.let { ChatCommand.Mod(it, name == "mod") } ?: usage()
            "vip", "unvip" -> user?.let { ChatCommand.Vip(it, name == "vip") } ?: usage()
            "announce" -> rest.ifBlank { null }?.let { ChatCommand.Announce(it) } ?: usage()
            "shoutout" -> user?.let { ChatCommand.Shoutout(it) } ?: usage()
            "raid" -> user?.let { ChatCommand.Raid(it) } ?: usage()
            "unraid" -> ChatCommand.Unraid
            "color" -> args.firstOrNull()?.let { ChatCommand.Color(normalizeColor(it)) } ?: usage()
            else -> ChatCommand.Unknown(name)
        }
    }

    /**
     * "30", "30s", "10m", "2h", "1d", "1w" -> seconds. A number without unit is multiplied by
     * [defaultUnit] (seconds by default). Returns null for invalid input.
     */
    fun parseDuration(text: String, defaultUnit: Int = 1): Int? {
        val match = Regex("^(\\d+)([smhdw]?)$").matchEntire(text.lowercase()) ?: return null
        val n = match.groupValues[1].toLongOrNull() ?: return null
        val factor = when (match.groupValues[2]) {
            "" -> defaultUnit
            "s" -> 1
            "m" -> 60
            "h" -> 3600
            "d" -> 86_400
            else -> 604_800
        }
        return (n * factor).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    /** "BlueViolet" / "blue_violet" -> "blue_violet"; hex colors stay as they are. */
    private fun normalizeColor(color: String): String =
        if (color.startsWith("#")) color
        else color.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
}
