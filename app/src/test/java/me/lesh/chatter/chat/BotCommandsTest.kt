package me.lesh.chatter.chat

import kotlinx.serialization.json.decodeFromJsonElement
import me.lesh.chatter.net.AppJson
import me.lesh.chatter.net.BotCommandLists
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bots' command lists, read the way the app reads them, and what the input suggests from them. */
class BotCommandsTest {
    private inline fun <reified T> read(json: String): T = AppJson.decodeFromJsonElement(AppJson.parseToJsonElement(json))

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
        assertEquals(listOf(BotCommand(Bot.Supibot, "percent", listOf("%"), "Rolls a random percentage.")), commands)
        assertEquals("\$percent", commands.single().typed)
    }

    @Test
    fun streamElementsKeepsWhatEveryoneMayUse() {
        val commands = BotCommandLists.streamElements(
            read(
                """[
                    {"command":"discord","aliases":["dc"],"reply":"discord.gg/x","enabled":true,"hidden":false,"accessLevel":100},
                    {"command":"off","reply":"","enabled":false,"hidden":false,"accessLevel":100},
                    {"command":"secret","reply":"","enabled":true,"hidden":true,"accessLevel":100},
                    {"command":"modonly","reply":"","enabled":true,"hidden":false,"accessLevel":500}
                ]""",
            ),
        )
        assertEquals(listOf(BotCommand(Bot.StreamElements, "discord", listOf("dc"), "discord.gg/x")), commands)
    }

    @Test
    fun fossabotKeepsCommandsWithoutRoles() {
        val commands = BotCommandLists.fossabot(
            read(
                """{"roles":[],"commands":[
                    {"id":"1","name":"tts","response":"Only for donations","type":"custom","aliases":[],"enabled_online":true,"enabled_offline":true,"role_ids":[]},
                    {"id":"2","name":"off","response":"","aliases":[],"enabled_online":false,"enabled_offline":false,"role_ids":[]},
                    {"id":"3","name":"mods","response":"","aliases":[],"enabled_online":true,"enabled_offline":true,"role_ids":["CAEQERoDMTMz"]}
                ]}""",
            ),
        )
        assertEquals(listOf(BotCommand(Bot.Fossabot, "tts", emptyList(), "Only for donations")), commands)
    }

    /** A Nightbot name without "!" is a word it reacts to anywhere in a message. */
    @Test
    fun nightbotKeepsCommandsThatStartWithTheMark() {
        val commands = BotCommandLists.nightbot(
            read(
                """{"_total":4,"status":200,"commands":[
                    {"name":"!song","message":"${'$'}(song)","userLevel":"everyone","coolDown":30},
                    {"name":"xd","message":"moon2M","userLevel":"everyone"},
                    {"name":"!ban","message":"","userLevel":"moderator"},
                    {"name":"!","message":"","userLevel":"everyone"}
                ]}""",
            ),
        )
        assertEquals(listOf(BotCommand(Bot.Nightbot, "song", emptyList(), "\$(song)")), commands)
    }

    @Test
    fun namesComeBeforeAliases() {
        val commands = listOf(
            command("percent", "%"),
            command("ping"),
            command("weather", "pi"),
            command("Pizza"),
        )
        assertEquals(listOf("ping", "Pizza", "weather"), BotCommands.rank("pi", commands).map { it.name })
    }

    @Test
    fun nothingTypedListsEverythingInOrder() {
        val commands = listOf(command("b"), command("a"), command("c"))
        assertEquals(listOf("a", "b", "c"), BotCommands.rank("", commands).map { it.name })
    }

    /** A channel moving from one bot to another often keeps a command in both. */
    @Test
    fun aCommandInTwoBotsIsSuggestedOnce() {
        val commands = listOf(
            BotCommand(Bot.StreamElements, "discord", emptyList(), "old"),
            BotCommand(Bot.Fossabot, "discord", emptyList(), "new"),
        )
        assertEquals(listOf(Bot.Fossabot), BotCommands.rank("dis", commands).map { it.bot })
    }

    private fun command(name: String, vararg aliases: String) = BotCommand(Bot.Supibot, name, aliases.toList(), "")
}
