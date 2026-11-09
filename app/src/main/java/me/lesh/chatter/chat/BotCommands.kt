package me.lesh.chatter.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.lesh.chatter.net.BotApi
import me.lesh.chatter.util.runCatchingCancellable

/** The chat bots whose commands are suggested, and the character their commands start with. */
enum class Bot(val prefix: Char, val label: String) {
    Supibot('$', "Supibot"),
    Fossabot('!', "Fossabot"),
    StreamElements('!', "StreamElements"),
    Nightbot('!', "Nightbot"),
}

/** [name] is without the prefix. [description] is Supibot's text, or what the other bots answer. */
data class BotCommand(val bot: Bot, val name: String, val aliases: List<String>, val description: String) {
    val typed: String get() = "${bot.prefix}$name"
}

/**
 * Bot commands for the input. Supibot's are the same everywhere; the "!" bots have their own per
 * channel. Each list is downloaded the first time a command is typed and kept for [MAX_AGE_MS], so
 * nothing is asked for until someone types one.
 */
class BotCommands(private val api: BotApi, private val scope: CoroutineScope) {
    /**
     * One download, started in the app's scope so that a keystroke cancelling the suggestion does
     * not cancel it. A failed one is tried again with the next command.
     */
    private inner class Loaded(fetch: suspend () -> List<BotCommand>) {
        private val at = System.currentTimeMillis()

        @Volatile private var failed = false

        val commands: Deferred<List<BotCommand>> = scope.async {
            runCatchingCancellable { fetch() }.getOrElse { failed = true; emptyList() }
        }

        val usable: Boolean get() = !failed && System.currentTimeMillis() - at < MAX_AGE_MS
    }

    private var supibot: Loaded? = null
    private val channels = HashMap<String, Loaded>()

    /** The commands that [typed], the first word of the input, could become. */
    suspend fun suggest(channel: String, typed: String): List<BotCommand> {
        val loaded = when (typed.firstOrNull()) {
            Bot.Supibot.prefix -> supibot()
            '!' -> channel(channel.lowercase())
            else -> return emptyList()
        }
        return rank(typed.substring(1), loaded.commands.await())
    }

    private fun supibot(): Loaded = synchronized(this) {
        supibot?.takeIf { it.usable } ?: Loaded { api.supibot() }.also { supibot = it }
    }

    private fun channel(login: String): Loaded = synchronized(this) {
        channels[login]?.takeIf { it.usable } ?: Loaded {
            coroutineScope {
                listOf(
                    async { api.fossabot(login) },
                    async { api.streamElements(login) },
                    async { api.nightbot(login) },
                ).awaitAll().filterNotNull().flatten()
            }
        }.also { channels[login] = it }
    }

    companion object {
        /** How long a command list is used before it is downloaded again: streamers add commands. */
        private const val MAX_AGE_MS = 60 * 60 * 1000L

        private const val LIMIT = 40

        /**
         * Name prefix first, then alias prefix, each in alphabetical order; one command per name, as
         * a channel can have it in two bots. Nothing typed after the prefix lists them all.
         */
        fun rank(query: String, commands: List<BotCommand>): List<BotCommand> {
            val byName = compareBy<BotCommand>({ it.name.lowercase() }, { it.bot.ordinal })
            val names = commands.filter { it.name.startsWith(query, ignoreCase = true) }.sortedWith(byName)
            val aliases = commands
                .filter { c -> !c.name.startsWith(query, ignoreCase = true) && c.aliases.any { it.startsWith(query, ignoreCase = true) } }
                .sortedWith(byName)
            return (names + aliases).distinctBy { it.name.lowercase() }.take(LIMIT)
        }
    }
}
