package me.lesh.chatter.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.lesh.chatter.chat.Bot
import me.lesh.chatter.chat.BotCommand
import okhttp3.OkHttpClient

// ---- Supibot -------------------------------------------------------------------

@Serializable
data class SupibotCommands(val data: List<SupibotCommand> = emptyList())

@Serializable
data class SupibotCommand(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
    val flags: List<String> = emptyList(),
)

// ---- StreamElements --------------------------------------------------------------

@Serializable
data class StreamElementsChannel(@SerialName("_id") val id: String)

@Serializable
data class StreamElementsCommand(
    val command: String,
    val aliases: List<String> = emptyList(),
    val reply: String = "",
    val enabled: Boolean = false,
    val hidden: Boolean = false,
    /** 100 is everyone; more is subs, mods and the streamer. */
    val accessLevel: Int = 100,
)

// ---- Fossabot -------------------------------------------------------------------

@Serializable
data class FossabotChannel(val channel: FossabotChannelId)

@Serializable
data class FossabotChannelId(val id: String)

@Serializable
data class FossabotCommands(val commands: List<FossabotCommand> = emptyList())

@Serializable
data class FossabotCommand(
    val name: String,
    val aliases: List<String> = emptyList(),
    val response: String = "",
    @SerialName("enabled_online") val enabledOnline: Boolean = true,
    @SerialName("enabled_offline") val enabledOffline: Boolean = true,
    /** Empty for a command everyone may use. */
    @SerialName("role_ids") val roleIds: List<String> = emptyList(),
)

// ---- Nightbot ---------------------------------------------------------------------

@Serializable
data class NightbotChannel(val channel: NightbotChannelId)

@Serializable
data class NightbotChannelId(@SerialName("_id") val id: String)

@Serializable
data class NightbotCommands(val commands: List<NightbotCommand> = emptyList())

@Serializable
data class NightbotCommand(val name: String, val message: String = "", val userLevel: String = "everyone")

/** Only commands that everyone in the chat may use; the others would only answer with silence. */
internal object BotCommandLists {
    /** Supibot's own and restricted commands ("system", "developer", "whitelist") are left out. */
    fun supibot(list: SupibotCommands): List<BotCommand> = list.data
        .filter { c -> c.flags.none { it in SUPIBOT_HIDDEN_FLAGS } }
        .map { BotCommand(Bot.Supibot, it.name, it.aliases, it.description.orEmpty()) }

    fun streamElements(list: List<StreamElementsCommand>): List<BotCommand> = list
        .filter { it.enabled && !it.hidden && it.accessLevel <= STREAM_ELEMENTS_EVERYONE }
        .map { BotCommand(Bot.StreamElements, it.command, it.aliases, it.reply) }

    fun fossabot(list: FossabotCommands): List<BotCommand> = list.commands
        .filter { (it.enabledOnline || it.enabledOffline) && it.roleIds.isEmpty() }
        .map { BotCommand(Bot.Fossabot, it.name, it.aliases, it.response) }

    /** Nightbot names carry their "!"; one without is a word it reacts to anywhere, not a command. */
    fun nightbot(list: NightbotCommands): List<BotCommand> = list.commands
        .filter { it.name.startsWith("!") && it.name.length > 1 && it.userLevel == "everyone" }
        .map { BotCommand(Bot.Nightbot, it.name.substring(1), emptyList(), it.message) }

    private val SUPIBOT_HIDDEN_FLAGS = setOf("system", "developer", "whitelist")
    private const val STREAM_ELEMENTS_EVERYONE = 100
}

/** The public command lists of the chat bots Chatter suggests commands for. None needs a login. */
class BotApi(private val http: OkHttpClient) {
    suspend fun supibot(): List<BotCommand> =
        BotCommandLists.supibot(http.getJson("https://supinic.com/api/bot/command/list"))

    /** Null when the channel has no StreamElements account. */
    suspend fun streamElements(login: String): List<BotCommand>? {
        val channel = http.getJsonOrNull<StreamElementsChannel>("https://api.streamelements.com/kappa/v2/channels/$login") ?: return null
        return BotCommandLists.streamElements(http.getJson("https://api.streamelements.com/kappa/v2/bot/commands/${channel.id}/public"))
    }

    /** Null when the channel has no Fossabot. */
    suspend fun fossabot(login: String): List<BotCommand>? {
        val channel = http.getJsonOrNull<FossabotChannel>("https://api.fossabot.com/v2/cached/channels/by-slug/$login") ?: return null
        return BotCommandLists.fossabot(http.getJson("https://api.fossabot.com/v2/cached/channels/${channel.channel.id}/commands"))
    }

    /** Null when the channel has no Nightbot. */
    suspend fun nightbot(login: String): List<BotCommand>? {
        val channel = http.getJsonOrNull<NightbotChannel>("https://api.nightbot.tv/1/channels/t/$login") ?: return null
        return BotCommandLists.nightbot(
            http.getJson("https://api.nightbot.tv/1/commands", headers = mapOf("Nightbot-Channel" to channel.channel.id)),
        )
    }
}
