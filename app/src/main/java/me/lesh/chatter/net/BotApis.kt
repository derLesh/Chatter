package me.lesh.chatter.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.lesh.chatter.chat.Bot
import me.lesh.chatter.chat.BotCommand
import me.lesh.chatter.chat.BotCommands
import me.lesh.chatter.chat.BotLists
import me.lesh.chatter.chat.ChannelBot
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

/** One row per name the channel ever had. */
@Serializable
data class SupibotChannels(val data: List<SupibotChannel> = emptyList())

@Serializable
data class SupibotChannel(val mode: String = "")

// ---- PotatBot ------------------------------------------------------------------

@Serializable
data class PotatCommand(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val category: String = "",
    /** 1 is everyone; more is the people who run the bot. */
    val level: Int = 1,
    val userRequires: String = "NONE",
    val isDisabled: Boolean = false,
    /** Null for every platform. */
    val availablePlatforms: List<String>? = null,
)

/** An unknown name comes back as one entry without a channel, not as a 404. */
@Serializable
data class PotatUsers(val data: List<PotatUser> = emptyList())

@Serializable
data class PotatUser(val channel: PotatChannel? = null)

@Serializable
data class PotatChannel(
    val state: String = "",
    val settings: PotatChannelSettings = PotatChannelSettings(),
    val commands: List<PotatChannelCommand> = emptyList(),
)

/** [prefix] is one text, or a list of them when the channel set several. */
@Serializable
data class PotatChannelSettings(val prefix: JsonElement? = null)

/** A command the channel added. Without a [name] it is a pattern PotatBot reacts to anywhere. */
@Serializable
data class PotatChannelCommand(
    val name: String? = null,
    val response: String = "",
    val help: String? = null,
    /** "RESPONSE" answers with [response]; "COMMAND" runs another command. */
    val type: String = "",
    val active: Boolean = true,
    @SerialName("user_requires") val userRequires: String = "NONE",
    /** Set when a channel point reward starts it instead of a message. */
    @SerialName("reward_id") val rewardId: JsonElement? = null,
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

// ---- Streamlabs -------------------------------------------------------------------

/** [token] stands for the channel in every other request. */
@Serializable
data class StreamlabsUser(val token: String)

/** Streamlabs is on most channels for its alerts; [enabled] says whether its bot is in the chat. */
@Serializable
data class StreamlabsCloudbot(val enabled: Boolean = false)

/** The channel's own commands have a [response], Streamlabs' built-in ones a [description]. */
@Serializable
data class StreamlabsCommand(
    val command: String,
    val response: String? = null,
    val description: String? = null,
    val permission: StreamlabsPermission? = null,
    @SerialName("static_permission") val staticPermission: StreamlabsPermission? = null,
)

/** One bit per group of people; the lowest is everyone. */
@Serializable
data class StreamlabsPermission(val level: Int = 1)

/** Only commands that everyone in the chat may use; the others would only answer with silence. */
internal object BotCommandLists {
    /** Supibot's own and restricted commands ("system", "developer", "whitelist") are left out. */
    fun supibot(list: SupibotCommands): List<BotCommand> = list.data
        .filter { c -> c.flags.none { it in SUPIBOT_HIDDEN_FLAGS } }
        .map { command(it.name, it.aliases, it.description.orEmpty()) }

    /** Null where Supibot is not, or only reads along. */
    fun supibotIn(channels: SupibotChannels): ChannelBot? =
        if (channels.data.any { it.mode in SUPIBOT_ANSWERING }) ChannelBot(prefixes = listOf("$")) else null

    fun potatBot(list: List<PotatCommand>): List<BotCommand> = list
        .filter { it.level <= 1 && it.userRequires == "NONE" && !it.isDisabled && it.category !in POTAT_HIDDEN_CATEGORIES }
        .filter { it.availablePlatforms?.contains("TWITCH") != false }
        // PotatBot lists phrases like ", prefix" among the aliases.
        .map { c -> command(c.name, c.aliases.filter { ' ' !in it }, c.description) }

    /**
     * Null where PotatBot is not. A command the channel added is typed after the prefix, unless
     * its name starts with a sign of its own: "!obliterate" is typed just so.
     */
    fun potatBotIn(users: PotatUsers): ChannelBot? {
        val channel = users.data.firstOrNull()?.channel?.takeIf { it.state == "JOINED" } ?: return null
        val prefix = channel.settings.prefix
        val prefixes = (if (prefix is JsonArray) prefix.toList() else listOfNotNull(prefix))
            .mapNotNull { text -> (text as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(POTAT_DEFAULT_PREFIX) }
        val commands = channel.commands
            .filter { it.active && it.userRequires == "NONE" && it.rewardId == null }
            .mapNotNull { c ->
                val name = c.name?.takeIf { it.isNotBlank() && ' ' !in it } ?: return@mapNotNull null
                val typed = when {
                    name.startsWith(POTAT_ANY_PREFIX) -> prefixes.first() + name.removePrefix(POTAT_ANY_PREFIX)
                    name.first().isLetterOrDigit() -> prefixes.first() + name
                    else -> name
                }
                command(typed, emptyList(), c.help ?: c.response.takeIf { c.type == "RESPONSE" }.orEmpty())
            }
        return ChannelBot(prefixes, commands.filter { BotCommands.startsLikeCommand(it.name) })
    }

    fun streamElements(list: List<StreamElementsCommand>): ChannelBot = ChannelBot(
        commands = list
            .filter { it.enabled && !it.hidden && it.accessLevel <= STREAM_ELEMENTS_EVERYONE }
            .map { c -> command("!${c.command}", c.aliases.map { "!$it" }, c.reply) },
    )

    fun fossabot(list: FossabotCommands): ChannelBot = ChannelBot(
        commands = list.commands
            .filter { (it.enabledOnline || it.enabledOffline) && it.roleIds.isEmpty() }
            .map { c -> command("!${c.name}", c.aliases.map { "!$it" }, c.response) },
    )

    /** Nightbot names carry their "!"; one without a sign is a word it reacts to, not a command. */
    fun nightbot(list: NightbotCommands): ChannelBot = ChannelBot(
        commands = list.commands
            .filter { it.name.length > 1 && BotCommands.startsLikeCommand(it.name) && it.userLevel == "everyone" }
            .map { command(it.name, emptyList(), it.message) },
    )

    /** As with Nightbot, the names carry their "!". */
    fun streamlabs(list: List<StreamlabsCommand>): ChannelBot = ChannelBot(
        commands = list
            .filter { it.command.length > 1 && BotCommands.startsLikeCommand(it.command) }
            .filter { c -> (c.permission ?: c.staticPermission)?.let { it.level and STREAMLABS_EVERYONE != 0 } != false }
            .map { command(it.command, emptyList(), it.response ?: it.description.orEmpty()) },
    )

    /** The text is cut: a chip shows one line of it, and answers can be a whole paragraph. */
    private fun command(name: String, aliases: List<String>, text: String) =
        BotCommand(name, aliases, text.replace(WHITESPACE, " ").trim().take(DESCRIPTION_CHARS))

    private val SUPIBOT_HIDDEN_FLAGS = setOf("system", "developer", "whitelist")
    private val SUPIBOT_ANSWERING = setOf("Write", "VIP", "Moderator")
    private val POTAT_HIDDEN_CATEGORIES = setOf("development", "deprecated")
    /** Stands for whichever prefix the channel has. */
    private const val POTAT_ANY_PREFIX = "{any}"
    private const val POTAT_DEFAULT_PREFIX = "#"
    private const val STREAM_ELEMENTS_EVERYONE = 100
    private const val STREAMLABS_EVERYONE = 1
    private const val DESCRIPTION_CHARS = 120
    private val WHITESPACE = Regex("\\s+")
}

/** The public command lists of the chat bots Chatter suggests commands for. None needs a login. */
class BotApi(private val http: OkHttpClient) : BotLists {
    override suspend fun everywhere(bot: Bot): List<BotCommand> = when (bot) {
        Bot.Supibot -> BotCommandLists.supibot(http.getJson("https://supinic.com/api/bot/command/list"))
        Bot.PotatBot -> BotCommandLists.potatBot(http.getJson("https://api.potat.app/help"))
        Bot.Fossabot, Bot.Nightbot, Bot.StreamElements, Bot.Streamlabs -> emptyList()
    }

    override suspend fun inChannel(bot: Bot, login: String): ChannelBot? = when (bot) {
        Bot.Supibot -> BotCommandLists.supibotIn(http.getJson("https://supinic.com/api/bot/channel/previousList?username=$login"))
        Bot.PotatBot -> BotCommandLists.potatBotIn(http.getJson("https://api.potat.app/users/$login"))
        Bot.Fossabot -> fossabot(login)
        Bot.Nightbot -> nightbot(login)
        Bot.StreamElements -> streamElements(login)
        Bot.Streamlabs -> streamlabs(login)
    }

    private suspend fun streamElements(login: String): ChannelBot? {
        val channel = http.getJsonOrNull<StreamElementsChannel>("https://api.streamelements.com/kappa/v2/channels/$login") ?: return null
        return BotCommandLists.streamElements(http.getJson("https://api.streamelements.com/kappa/v2/bot/commands/${channel.id}/public"))
    }

    private suspend fun fossabot(login: String): ChannelBot? {
        val channel = http.getJsonOrNull<FossabotChannel>("https://api.fossabot.com/v2/cached/channels/by-slug/$login") ?: return null
        return BotCommandLists.fossabot(http.getJson("https://api.fossabot.com/v2/cached/channels/${channel.channel.id}/commands"))
    }

    private suspend fun nightbot(login: String): ChannelBot? {
        val channel = http.getJsonOrNull<NightbotChannel>("https://api.nightbot.tv/1/channels/t/$login") ?: return null
        return BotCommandLists.nightbot(
            http.getJson("https://api.nightbot.tv/1/commands", headers = mapOf("Nightbot-Channel" to channel.channel.id)),
        )
    }

    private suspend fun streamlabs(login: String): ChannelBot? {
        val user = try {
            http.getJson<StreamlabsUser>("$STREAMLABS/user/$login")
        } catch (e: HttpException) {
            // Streamlabs answers 401 for a name it does not know.
            if (e.code == 401 || e.code == 404) return null else throw e
        }
        if (!http.getJson<StreamlabsCloudbot>("$STREAMLABS/${user.token}/cloudbot/status").enabled) return null
        return BotCommandLists.streamlabs(http.getJson("$STREAMLABS/${user.token}/cloudbot/commands"))
    }

    private companion object {
        const val STREAMLABS = "https://streamlabs.com/api/v6"
    }
}
