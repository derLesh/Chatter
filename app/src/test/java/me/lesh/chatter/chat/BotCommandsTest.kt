package me.lesh.chatter.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.decodeFromJsonElement
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.BotCommandLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The bots' lists, read the way the app reads them, what the input suggests from them, and how
 * rarely a bot's server is asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotCommandsTest {
    private inline fun <reified T> read(json: String): T = AppJson.decodeFromJsonElement(AppJson.parseToJsonElement(json))

    // ---- Reading the lists -------------------------------------------------------

    @Test
    fun supibotLeavesOutItsRestrictedCommands() {
        val commands = BotCommandLists.supibot(
            read(
                """{"statusCode":200,"data":[
                    {"name":"percent","aliases":["%"],"description":"Rolls a random percentage.","cooldown":5000,"flags":["mention","pipe"]},
                    {"name":"7tv","aliases":[],"description":"Manages emotes.","flags":["whitelist"]},
                    {"name":"debug","aliases":[],"description":null,"flags":["developer","system"]}
                ]}""",
            ),
        )
        assertEquals(listOf(BotCommand("percent", listOf("%"), "Rolls a random percentage.")), commands)
    }

    @Test
    fun supibotIsOnlyWhereItAnswers() {
        fun channel(json: String) = BotCommandLists.supibotIn(read("""{"statusCode":200,"data":$json,"error":null}"""))
        assertEquals(ChannelBot(prefixes = listOf("$")), channel("""[{"ID":14,"name":"forsen","specificID":"22484632","mode":"VIP"}]"""))
        // A channel that changed its name has a row for each.
        assertEquals(
            ChannelBot(prefixes = listOf("$")),
            channel("""[{"ID":1,"name":"old","mode":"Inactive"},{"ID":2,"name":"new","mode":"Write"}]"""),
        )
        assertNull(channel("""[{"ID":185,"name":"shroud","specificID":"37402112","mode":"Inactive"}]"""))
        assertNull(channel("""[{"ID":3,"name":"reader","mode":"Read"}]"""))
        assertNull(channel("[]"))
    }

    @Test
    fun potatBotKeepsWhatEveryoneMayUseOnTwitch() {
        val commands = BotCommandLists.potatBot(
            read(
                """[
                    {"name":"prefix","description":"View the bot prefix.","category":"settings","aliases":["setprefix",", prefix"],
                     "flags":[{"name":"x","level":1}],"level":1,"userRequires":"NONE","isDisabled":false},
                    {"name":"ping","description":"Pong","category":"utilities","aliases":[],"level":1,"userRequires":"NONE",
                     "gatewayOnly":true,"availablePlatforms":["TWITCH","DISCORD","KICK"]},
                    {"name":"eval","description":"","category":"development","aliases":[],"level":4,"userRequires":"NONE"},
                    {"name":"dalle","description":"","category":"fun","aliases":[],"level":2,"userRequires":"NONE"},
                    {"name":"pin","description":"","category":"moderation","aliases":[],"level":1,"userRequires":"MOD"},
                    {"name":"neko","description":"","category":"deprecated","aliases":[],"level":1,"userRequires":"NONE"},
                    {"name":"kickonly","description":"","category":"fun","aliases":[],"level":1,"userRequires":"NONE","availablePlatforms":["KICK"]}
                ]""",
            ),
        )
        assertEquals(
            listOf(BotCommand("prefix", listOf("setprefix"), "View the bot prefix."), BotCommand("ping", emptyList(), "Pong")),
            commands,
        )
    }

    @Test
    fun potatBotIsReadWithTheChannelsPrefixes() {
        fun channel(json: String) = BotCommandLists.potatBotIn(read("""{"data":[{"user":{"user_id":1},"channel":$json}],"statusCode":200}"""))
        assertEquals(ChannelBot(listOf("#")), channel("""{"settings":{"prefix":"#","language":"english"},"state":"JOINED","commands":[]}"""))
        assertEquals(listOf("#", "-"), channel("""{"settings":{"prefix":["#","-"]},"state":"JOINED"}""")?.prefixes)
        assertEquals(listOf("#"), channel("""{"settings":{},"state":"JOINED"}""")?.prefixes)
        assertNull(channel("""{"settings":{"prefix":"#"},"state":"PARTED"}"""))
        assertNull(channel("null"))
        // A name PotatBot does not know.
        assertNull(BotCommandLists.potatBotIn(read("""{"data":[{"errors":{"message":"User not found"}}],"statusCode":404}""")))
    }

    @Test
    fun potatBotAddsTheCommandsOfTheChannel() {
        val bot = BotCommandLists.potatBotIn(
            read(
                """{"data":[{"channel":{"settings":{"prefix":["+","#"]},"state":"JOINED","commands":[
                    {"name":"!potatbotat","trigger":"","response":"A chatbot by @RyanPotat","active":true,"user_requires":"NONE","type":"RESPONSE","reward_id":null},
                    {"name":"welcome","trigger":"","response":"","help":"Adds an emote","active":true,"user_requires":"NONE","type":"COMMAND","run_command":"pipe rc"},
                    {"name":"{any}discord","trigger":"","response":"discord.gg/x","active":true,"user_requires":"NONE","type":"RESPONSE"},
                    {"name":"discharge","trigger":"","response":"const d = new Date()","active":true,"user_requires":"NONE","type":"COMMAND"},
                    {"name":null,"trigger":"/^xdd$/","response":"xdd","active":true,"user_requires":"NONE","type":"RESPONSE"},
                    {"name":"off","trigger":"","response":"","active":false,"user_requires":"NONE","type":"RESPONSE"},
                    {"name":"mods","trigger":"","response":"","active":true,"user_requires":"MOD","type":"RESPONSE"},
                    {"name":"reward","trigger":"","response":"","active":true,"user_requires":"NONE","type":"RESPONSE","reward_id":"abc"}
                ]}}]}""",
            ),
        )
        assertEquals(
            listOf(
                BotCommand("!potatbotat", emptyList(), "A chatbot by @RyanPotat"),
                BotCommand("+welcome", emptyList(), "Adds an emote"),
                BotCommand("+discord", emptyList(), "discord.gg/x"),
                BotCommand("+discharge", emptyList(), ""),
            ),
            bot?.commands,
        )
    }

    @Test
    fun streamElementsKeepsWhatEveryoneMayUse() {
        val bot = BotCommandLists.streamElements(
            read(
                """[
                    {"command":"discord","aliases":["dc"],"reply":"discord.gg/x","enabled":true,"hidden":false,"accessLevel":100},
                    {"command":"off","reply":"","enabled":false,"hidden":false,"accessLevel":100},
                    {"command":"secret","reply":"","enabled":true,"hidden":true,"accessLevel":100},
                    {"command":"modonly","reply":"","enabled":true,"hidden":false,"accessLevel":500}
                ]""",
            ),
        )
        assertEquals(ChannelBot(commands = listOf(BotCommand("!discord", listOf("!dc"), "discord.gg/x"))), bot)
    }

    @Test
    fun fossabotKeepsCommandsWithoutRoles() {
        val bot = BotCommandLists.fossabot(
            read(
                """{"roles":[],"commands":[
                    {"id":"1","name":"tts","response":"Only for donations","type":"custom","aliases":[],"enabled_online":true,"enabled_offline":true,"role_ids":[]},
                    {"id":"2","name":"off","response":"","aliases":[],"enabled_online":false,"enabled_offline":false,"role_ids":[]},
                    {"id":"3","name":"mods","response":"","aliases":[],"enabled_online":true,"enabled_offline":true,"role_ids":["CAEQERoDMTMz"]}
                ]}""",
            ),
        )
        assertEquals(listOf(BotCommand("!tts", emptyList(), "Only for donations")), bot.commands)
    }

    /** A Nightbot name without a sign is a word it reacts to anywhere in a message. */
    @Test
    fun nightbotKeepsCommandsThatStartWithASign() {
        val bot = BotCommandLists.nightbot(
            read(
                """{"_total":5,"status":200,"commands":[
                    {"name":"!song","message":"${'$'}(song)","userLevel":"everyone","coolDown":30},
                    {"name":"?help","message":"Ask a mod","userLevel":"everyone"},
                    {"name":"xd","message":"moon2M","userLevel":"everyone"},
                    {"name":"!ban","message":"","userLevel":"moderator"},
                    {"name":"!","message":"","userLevel":"everyone"}
                ]}""",
            ),
        )
        assertEquals(listOf(BotCommand("!song", emptyList(), "\$(song)"), BotCommand("?help", emptyList(), "Ask a mod")), bot.commands)
    }

    /** Streamlabs lists its built-in commands with a description, the channel's with their answer. */
    @Test
    fun streamlabsKeepsWhatEveryoneMayUse() {
        val bot = BotCommandLists.streamlabs(
            read(
                """[
                    {"command":"!addcommand","description":"Allows a mod to add a command.","static_permission":{"level":32}},
                    {"command":"!raffle","description":"Join the active raffle.","permission":{"level":1}},
                    {"command":"!addquote","description":"Add a quote through chat.","permission":{"level":32}},
                    {"command":"!redeem","description":"Redeem items","static_permission":{"level":1}},
                    {"command":"!age","response":"{touser.name} Nick is 31 years old.","cost":{"base":0,"mod":0},"permission":{"level":1}},
                    {"command":"!subs","response":"Subs only","cost":{"base":0},"permission":{"level":2}},
                    {"command":"hello","response":"Hi","permission":{"level":1}}
                ]""",
            ),
        )
        assertEquals(
            listOf(
                BotCommand("!raffle", emptyList(), "Join the active raffle."),
                BotCommand("!redeem", emptyList(), "Redeem items"),
                BotCommand("!age", emptyList(), "{touser.name} Nick is 31 years old."),
            ),
            bot.commands,
        )
    }

    @Test
    fun aLongAnswerIsCutToWhatAChipShows() {
        val answer = "word ".repeat(100)
        val bot = BotCommandLists.fossabot(read("""{"commands":[{"name":"rules","response":"line one\nline two $answer"}]}"""))
        val description = bot.commands.single().description
        assertTrue(description.startsWith("line one line two word"))
        assertEquals(120, description.length)
    }

    // ---- What is suggested -------------------------------------------------------

    @Test
    fun aCommandStartsWithASign() {
        listOf("!so", "\$ping", "#", "-help", "?", "+join").forEach { assertTrue(it, BotCommands.startsLikeCommand(it)) }
        // Twitch's own commands, names, emotes and plain words.
        listOf("", "/ban", ".me", "@name", ":smile", ";)", "hello", "7tv", "§x").forEach { assertFalse(it, BotCommands.startsLikeCommand(it)) }
    }

    @Test
    fun namesComeBeforeAliases() {
        val supibot = offered(Bot.Supibot, "$", command("percent", "pi%"), command("ping"), command("weather", "pi"), command("Pizza"))
        assertEquals(listOf("\$ping", "\$Pizza", "\$percent", "\$weather"), BotCommands.rank("\$pi", listOf(supibot)).map { it.typed })
    }

    @Test
    fun onlyThePrefixListsEverythingInOrder() {
        val fossabot = offered(Bot.Fossabot, "", command("!b"), command("!a"), command("!c"))
        assertEquals(listOf("!a", "!b", "!c"), BotCommands.rank("!", listOf(fossabot)).map { it.typed })
    }

    @Test
    fun anotherSignSuggestsNothing() {
        val fossabot = offered(Bot.Fossabot, "", command("!discord", "!dc"))
        assertEquals(emptyList<BotSuggestion>(), BotCommands.rank("\$d", listOf(fossabot)))
    }

    /** A channel moving from one bot to another often keeps a command in both. */
    @Test
    fun aCommandInTwoBotsIsSuggestedOnce() {
        val bots = listOf(
            offered(Bot.StreamElements, "", BotCommand("!discord", emptyList(), "old")),
            offered(Bot.Fossabot, "", BotCommand("!Discord", emptyList(), "new")),
        )
        assertEquals(listOf(BotSuggestion(Bot.Fossabot, "!Discord", "new")), BotCommands.rank("!dis", bots))
    }

    // ---- How often the bots are asked --------------------------------------------

    /** Counts what is asked for, and answers from [channels] and [everywhere]. */
    private class FakeLists : BotLists {
        val channels = HashMap<Bot, ChannelBot?>()
        val everywhere = HashMap<Bot, List<BotCommand>>()
        val asked = ArrayList<String>()
        var broken = false

        override suspend fun inChannel(bot: Bot, login: String): ChannelBot? {
            asked += "$bot in $login"
            if (broken) throw IOException("no network")
            return channels[bot]
        }

        override suspend fun everywhere(bot: Bot): List<BotCommand> {
            asked += "$bot"
            if (broken) throw IOException("no network")
            return everywhere.getValue(bot)
        }
    }

    private class MemoryStore : BotListStore {
        val lists = HashMap<String, String>()
        override fun read(key: String): String? = lists[key]
        override fun write(key: String, json: String) {
            lists[key] = json
        }
        override fun clear() = lists.clear()
    }

    private class Phone(scope: TestScope) {
        val lists = FakeLists()
        val store = MemoryStore()
        var time = 1_000_000L
        var on = setOf(Bot.Supibot, Bot.Fossabot)
        private val scope = scope

        /** A fresh one is the app after a restart: only [store] is left. */
        fun botCommands() = BotCommands(lists, store, scope.backgroundScope, { on }, { time }, StandardTestDispatcher(scope.testScheduler))
    }

    private fun TestScope.phone() = Phone(this).apply {
        lists.channels[Bot.Supibot] = ChannelBot(prefixes = listOf("$"))
        lists.channels[Bot.Fossabot] = ChannelBot(commands = listOf(command("!discord")))
        lists.everywhere[Bot.Supibot] = listOf(command("ping"))
    }

    @Test
    fun aChannelsBotsAreAskedOnceADay() = runTest {
        val phone = phone()
        val commands = phone.botCommands()
        assertEquals(listOf("!discord"), commands.suggest("Forsen", "!d").map { it.typed })
        assertEquals(listOf("Supibot in forsen", "Fossabot in forsen"), phone.lists.asked)

        phone.time += 23 * HOUR
        commands.suggest("forsen", "!di")
        assertEquals(2, phone.lists.asked.size)

        phone.time += 2 * HOUR
        phone.lists.channels[Bot.Fossabot] = ChannelBot(commands = listOf(command("!discord"), command("!dpi")))
        assertEquals(listOf("!discord", "!dpi"), commands.suggest("forsen", "!d").map { it.typed })
        assertEquals(4, phone.lists.asked.size)
    }

    @Test
    fun aBotsOwnCommandsAreOnlyLoadedOnceItsPrefixIsTyped() = runTest {
        val phone = phone()
        val commands = phone.botCommands()
        commands.suggest("forsen", "!d")
        assertFalse("Supibot" in phone.lists.asked)

        assertEquals(listOf(BotSuggestion(Bot.Supibot, "\$ping", "")), commands.suggest("forsen", "\$p"))
        assertEquals(listOf("Supibot in forsen", "Fossabot in forsen", "Supibot"), phone.lists.asked)

        // A week, not a day.
        phone.time += 6 * 24 * HOUR
        phone.botCommands().suggest("nymn", "\$p")
        assertEquals(1, phone.lists.asked.count { it == "Supibot" })
    }

    @Test
    fun whatWasLoadedIsStillThereAfterARestart() = runTest {
        val phone = phone()
        phone.botCommands().suggest("forsen", "\$p")
        phone.lists.asked.clear()

        assertEquals(listOf("\$ping"), phone.botCommands().suggest("forsen", "\$p").map { it.typed })
        assertEquals(emptyList<String>(), phone.lists.asked)
    }

    @Test
    fun aChannelWithoutTheBotIsNotAskedAboutAgainEither() = runTest {
        val phone = phone()
        phone.lists.channels[Bot.Supibot] = null
        assertEquals(emptyList<BotSuggestion>(), phone.botCommands().suggest("forsen", "\$p"))
        phone.lists.asked.clear()

        assertEquals(emptyList<BotSuggestion>(), phone.botCommands().suggest("forsen", "\$p"))
        assertEquals(emptyList<String>(), phone.lists.asked)
    }

    @Test
    fun aBotTurnedOffIsNeverAsked() = runTest {
        val phone = phone()
        phone.on = setOf(Bot.Fossabot)
        val commands = phone.botCommands()
        assertEquals(emptyList<BotSuggestion>(), commands.suggest("forsen", "\$p"))
        assertEquals(listOf("Fossabot in forsen"), phone.lists.asked)

        phone.on = emptySet()
        assertEquals(emptyList<BotSuggestion>(), commands.suggest("forsen", "!d"))
        assertEquals(1, phone.lists.asked.size)
    }

    @Test
    fun aFailedDownloadKeepsTheOldListAndWaitsBeforeTheNext() = runTest {
        val phone = phone()
        val commands = phone.botCommands()
        commands.suggest("forsen", "!d")
        phone.time += 25 * HOUR
        phone.lists.asked.clear()
        phone.lists.broken = true

        assertEquals(listOf("!discord"), commands.suggest("forsen", "!d").map { it.typed })
        assertEquals(2, phone.lists.asked.size)
        commands.suggest("forsen", "!di")
        assertEquals(2, phone.lists.asked.size)

        phone.lists.broken = false
        phone.time += 11 * MINUTE
        commands.suggest("forsen", "!dis")
        assertEquals(4, phone.lists.asked.size)
    }

    @Test
    fun nothingIsAskedForAPlainWord() = runTest {
        val phone = phone()
        assertEquals(emptyList<BotSuggestion>(), phone.botCommands().suggest("forsen", "discord"))
        assertEquals(emptyList<String>(), phone.lists.asked)
    }

    @Test
    fun clearingTheCacheLoadsEverythingAgain() = runTest {
        val phone = phone()
        val commands = phone.botCommands()
        commands.suggest("forsen", "\$p")
        phone.lists.asked.clear()

        commands.forget()
        assertTrue(phone.store.lists.isEmpty())
        commands.suggest("forsen", "\$p")
        assertEquals(listOf("Supibot in forsen", "Fossabot in forsen", "Supibot"), phone.lists.asked)
    }

    private fun command(name: String, vararg aliases: String) = BotCommand(name, aliases.toList(), "")

    private fun offered(bot: Bot, prefix: String, vararg commands: BotCommand) = BotCommands.Offered(bot, prefix, commands.toList())

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
