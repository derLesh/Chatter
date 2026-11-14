package me.lesh.chatter.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.util.runCatchingCancellable
import java.io.File

/**
 * The chat bots whose commands are suggested, each with a switch in the settings. A bot that is
 * [sameEverywhere] has commands of its own; the others only have what each channel set up. One
 * with [helpPerCommand] says how to write a command only when asked about that one command.
 */
enum class Bot(val sameEverywhere: Boolean = false, val helpPerCommand: Boolean = false) {
    Supibot(sameEverywhere = true, helpPerCommand = true),
    PotatBot(sameEverywhere = true),
    ChronophylosBot(sameEverywhere = true),
    Fossabot,
    Moobot,
    Nightbot,
    StreamElements,
    Streamlabs,
}

/**
 * [name] is what is typed: all of it, like "!discord", or what follows the bot's prefix, like
 * "ping". [description] is the bot's own text, or what it answers. [usage] is what follows the
 * name, as [BotUsage.withoutCommand] writes it: "<ICAO-Typ> <dauer>". [parameters] are named
 * ones that can go anywhere after it: "alerts:true", or "format:" when a value follows.
 * [aliasUsages] is for a bot whose aliases take other arguments: "$remindme" is not "$remind".
 * An alias it does not name takes [usage]. [aliasNotes] say what such an alias does differently.
 */
@Serializable
data class BotCommand(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val usage: String = "",
    val parameters: List<String> = emptyList(),
    val aliasUsages: Map<String, String> = emptyMap(),
    val aliasNotes: Map<String, String> = emptyMap(),
)

/**
 * One bot in one channel. [commands] are the channel's own, named in full. A bot with the same
 * commands in every channel has them typed after one of [prefixes] here.
 */
@Serializable
data class ChannelBot(val prefixes: List<String> = emptyList(), val commands: List<BotCommand> = emptyList())

/** A command as the input offers it; [typed] is what tapping it writes, [arguments] what follows. */
data class BotSuggestion(
    val bot: Bot,
    val typed: String,
    val description: String,
    val arguments: String = "",
    val parameters: List<String> = emptyList(),
)

/** Where the command lists come from. */
interface BotLists {
    /** [bot] as the channel has it, or null when it is not there. */
    suspend fun inChannel(bot: Bot, login: String): ChannelBot?

    /** The commands [bot] has in every channel. */
    suspend fun everywhere(bot: Bot): List<BotCommand>

    /** [name] with how to write it, from a bot that is [Bot.helpPerCommand]; null when it has none. */
    suspend fun help(bot: Bot, name: String): BotCommand? = null
}

/** Keeps downloaded lists between app starts. A key is a bot's name, or "bot/channel". */
interface BotListStore {
    fun read(key: String): String?
    fun write(key: String, json: String)
    fun clear()
}

/** One small file per list. They can be downloaded again, so they live in the cache folder. */
class BotListFiles(private val folder: File) : BotListStore {
    override fun read(key: String): String? = runCatching { file(key).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(key: String, json: String) {
        runCatching { file(key).also { it.parentFile?.mkdirs() }.writeText(json) }
    }

    override fun clear() {
        folder.deleteRecursively()
    }

    private fun file(key: String) = File(folder, "$key.json")
}

/**
 * Bot commands for the input. A channel's bots are asked the first time a message there starts
 * like a command, so nothing is asked for a channel that is only read. Every answer is kept on the
 * phone, also that a bot is not in the channel, and asked for again after a day at the earliest.
 */
class BotCommands(
    private val lists: BotLists,
    private val store: BotListStore,
    private val scope: CoroutineScope,
    private val botsOn: () -> Set<Bot>,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** [format] is the [FORMAT] it was written in. */
    @Serializable
    internal class Kept<T>(val at: Long, val value: T, val format: Int = 0)

    /**
     * One list: the copy that is kept, and the download that replaces it once it is older than
     * [maxAgeMs]. The download runs in the app's scope, so that a keystroke cancelling the
     * suggestion does not cancel it.
     */
    private inner class KeptList<T>(
        private val key: String,
        private val serializer: KSerializer<Kept<T>>,
        private val maxAgeMs: Long,
        private val download: suspend () -> T,
    ) {
        private var kept: Kept<T>? = null
        /** Kept before the app read more from the bots: used, but downloaded again first. */
        private var outdated = false
        @Volatile private var read = false
        private var failedAt: Long? = null
        private var downloading: Deferred<Unit>? = null

        /** The copy that is kept, however old, without downloading one. */
        suspend fun kept(): T? {
            if (!read) withContext(io) { readStore() }
            return synchronized(this) { kept?.value }
        }

        /** Null without a copy, when none could be downloaded either. An old copy beats none. */
        suspend fun get(): T? {
            if (!read) withContext(io) { readStore() }
            val download = synchronized(this) {
                val time = now()
                val fresh = !outdated && kept?.let { time - it.at in 0 until maxAgeMs } == true
                val failedLately = failedAt?.let { time - it in 0 until RETRY_AFTER_MS } == true
                if (fresh || failedLately) null else downloading?.takeIf { it.isActive } ?: start().also { downloading = it }
            }
            if (download != null) withTimeoutOrNull(MAX_WAIT_MS) { download.await() }
            return synchronized(this) { kept?.value }
        }

        private fun readStore() = synchronized(this) {
            if (read) return@synchronized
            kept = store.read(key)?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }
            outdated = kept?.let { it.format != FORMAT } == true
            read = true
        }

        private fun start(): Deferred<Unit> = scope.async {
            runCatchingCancellable { download() }
                .onSuccess { value ->
                    val downloaded = Kept(now(), value, FORMAT)
                    synchronized(this@KeptList) {
                        kept = downloaded
                        outdated = false
                        failedAt = null
                    }
                    withContext(io) { store.write(key, AppJson.encodeToString(serializer, downloaded)) }
                }
                .onFailure { synchronized(this@KeptList) { failedAt = now() } }
            Unit
        }
    }

    private val channelLists = HashMap<String, KeptList<ChannelBot?>>()
    private val sharedLists = HashMap<Bot, KeptList<List<BotCommand>>>()
    private val helpLists = HashMap<String, KeptList<BotCommand?>>()

    /** The commands that [typed], the first word of the input, could become. */
    suspend fun suggest(channel: String, typed: String): List<BotSuggestion> = rank(typed, offered(channel, typed))

    /**
     * Null unless [typed] starts with a command, by its name or an alias, that has arguments.
     * Without [askHelp], a bot's help for the command is only used if it is kept already.
     */
    suspend fun hint(channel: String, typed: TypedArguments, askHelp: Boolean = true): ArgumentHint? {
        val (bot, command) = findCommand(typed.command, offered(channel, typed.command)) ?: return null
        val help = if (bot.bot.helpPerCommand && bot.prefix.isNotEmpty()) helpList(bot.bot, command.name) else null
        val withHelp = (if (askHelp) help?.get() else help?.kept()) ?: command
        val alias = withHelp.aliases.firstOrNull { (bot.prefix + it).equals(typed.command, ignoreCase = true) }
        return BotUsage.hint(suggestion(bot, withHelp, alias), typed)
    }

    private suspend fun offered(channel: String, typed: String): List<Offered> {
        val login = channel.lowercase()
        val bots = botsOn().sorted()
        if (bots.isEmpty() || !startsLikeCommand(typed) || !LOGIN.matches(login)) return emptyList()
        val here = coroutineScope {
            bots.map { bot -> async { channelList(bot, login).get()?.let { bot to it } } }.awaitAll().filterNotNull()
        }
        return here.flatMap { (bot, inChannel) ->
            val prefix = inChannel.prefixes.firstOrNull { typed.startsWith(it, ignoreCase = true) }
            listOfNotNull(
                Offered(bot, "", inChannel.commands),
                // Not before its prefix is typed: PotatBot's list is half a megabyte.
                prefix?.let { Offered(bot, it, sharedList(bot).get().orEmpty()) },
            )
        }
    }

    /** Drops every kept list, so that each is downloaded again when it is next needed. */
    suspend fun forget() {
        synchronized(this) {
            channelLists.clear()
            sharedLists.clear()
            helpLists.clear()
        }
        withContext(io) { store.clear() }
    }

    private fun channelList(bot: Bot, login: String): KeptList<ChannelBot?> = synchronized(this) {
        val key = "${bot.name}/$login"
        channelLists.getOrPut(key) { KeptList(key, CHANNEL_BOT, CHANNEL_MAX_AGE_MS) { lists.inChannel(bot, login) } }
    }

    private fun sharedList(bot: Bot): KeptList<List<BotCommand>> = synchronized(this) {
        sharedLists.getOrPut(bot) { KeptList(bot.name, COMMANDS, EVERYWHERE_MAX_AGE_MS) { lists.everywhere(bot) } }
    }

    /** Asked for the first time a command's arguments are typed. Null for a name that cannot be a file. */
    private fun helpList(bot: Bot, name: String): KeptList<BotCommand?>? = synchronized(this) {
        if (!COMMAND_NAME.matches(name)) return null
        val key = "${bot.name}-help/$name"
        helpLists.getOrPut(key) { KeptList(key, HELP, EVERYWHERE_MAX_AGE_MS) { lists.help(bot, name) } }
    }

    /** Commands of one bot that can be typed in a channel, each after [prefix]. */
    class Offered(val bot: Bot, val prefix: String, val commands: List<BotCommand>)

    companion object {
        /** Streamers add commands, so a day-old list of a channel is downloaded again. */
        private const val CHANNEL_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /** A bot's own commands change far less often. */
        private const val EVERYWHERE_MAX_AGE_MS = 7 * CHANNEL_MAX_AGE_MS

        /** How long a bot's server is left alone after a download from it failed. */
        private const val RETRY_AFTER_MS = 10 * 60 * 1000L

        /** One slow server must not hold back the commands of the other bots. */
        private const val MAX_WAIT_MS = 4_000L

        private const val LIMIT = 40

        /** Goes up when the app starts reading more from the bots' lists, like a command's usage. */
        private const val FORMAT = 1

        /** Twitch logins; anything else must not become a file name. */
        private val LOGIN = Regex("[a-z0-9_]{1,25}")
        private val COMMAND_NAME = Regex("[A-Za-z0-9_-]{1,40}")

        private val CHANNEL_BOT = Kept.serializer(ChannelBot.serializer().nullable)
        private val COMMANDS = Kept.serializer(ListSerializer(BotCommand.serializer()))
        private val HELP = Kept.serializer(BotCommand.serializer().nullable)

        /**
         * Bot commands start with a sign like "!" or "$". Not with "/" or ".", which Twitch reads
         * itself, nor with "@" for a name or ":" and ";" for an emote.
         */
        fun startsLikeCommand(word: String): Boolean {
            val first = word.firstOrNull() ?: return false
            return first in '!'..'~' && !first.isLetterOrDigit() && first !in "/.@:;"
        }

        /**
         * Names that start with [typed] first, then aliases that do, each its own suggestion, each
         * group in alphabetical order. A command a channel has in two bots is offered once.
         */
        fun rank(typed: String, offered: List<Offered>): List<BotSuggestion> {
            fun fits(text: String) = text.startsWith(typed, ignoreCase = true)
            val names = ArrayList<BotSuggestion>()
            val aliases = ArrayList<BotSuggestion>()
            for (bot in offered) for (command in bot.commands) {
                val suggestion = suggestion(bot, command)
                if (fits(suggestion.typed)) names += suggestion
                // Not for the sign alone, where every alias would follow every name.
                if (typed.length > 1) command.aliases.filter { fits(bot.prefix + it) }.forEach { aliases += suggestion(bot, command, it) }
            }
            val inOrder = compareBy<BotSuggestion>({ it.typed.lowercase() }, { it.bot.ordinal })
            return (names.sortedWith(inOrder) + aliases.sortedWith(inOrder)).distinctBy { it.typed.lowercase() }.take(LIMIT)
        }

        /** The command that [typed] is, by its name or an alias, ignoring case. */
        fun find(typed: String, offered: List<Offered>): BotSuggestion? =
            findCommand(typed, offered)?.let { (bot, command) -> suggestion(bot, command) }

        private fun findCommand(typed: String, offered: List<Offered>): Pair<Offered, BotCommand>? {
            for (bot in offered) for (command in bot.commands) {
                val names = listOf(command.name) + command.aliases
                if (names.any { (bot.prefix + it).equals(typed, ignoreCase = true) }) return bot to command
            }
            return null
        }

        /**
         * [command] as typed by its name, or by [alias] with the arguments that alias takes and,
         * before the description, what it does differently.
         */
        private fun suggestion(bot: Offered, command: BotCommand, alias: String? = null): BotSuggestion {
            val note = alias?.let { command.aliasNotes[it] }
            return BotSuggestion(
                bot.bot,
                bot.prefix + (alias ?: command.name),
                if (note == null) command.description else listOf(note, command.description).filter { it.isNotBlank() }.joinToString(". "),
                alias?.let { command.aliasUsages[it] } ?: command.usage,
                command.parameters,
            )
        }
    }
}
