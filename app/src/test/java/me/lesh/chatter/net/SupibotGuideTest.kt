package me.lesh.chatter.net

import kotlinx.serialization.json.decodeFromJsonElement
import me.lesh.chatter.chat.Bot
import me.lesh.chatter.chat.BotCommand
import me.lesh.chatter.chat.BotCommands
import me.lesh.chatter.chat.BotUsage
import me.lesh.chatter.chat.TypedArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-written guide to Supibot's commands, and how it beats what is read from the help. */
class SupibotGuideTest {
    private inline fun <reified T> read(json: String): T = AppJson.decodeFromJsonElement(AppJson.parseToJsonElement(json))

    /** A slip like a bare "or" or a lost bracket would split or swallow the ways of writing it. */
    @Test
    fun everyUsageReadsAsWritten() {
        for (usage in SupibotGuide.usages().filter { it.isNotEmpty() }) {
            val forms = BotUsage.forms(usage)
            assertEquals(usage, usage.split(" | ").size, forms.size)
            assertTrue(usage, forms.flatten().none { it.shown == "or" || it.shown.count { c -> c == '<' } != it.shown.count { c -> c == '>' } })
        }
    }

    @Test
    fun theGuideBeatsTheExamples() {
        val remind = BotCommandLists.supibotHelp(
            read(
                """{"data":{"prefix":"$","name":"remind","aliases":["notify","remindme","remindprivate"],"description":"Sets a notification.",
                    "dynamicDescription":["<code>${'$'}remindme test (time)</code>"],"params":[{"name":"in","type":"string"}]}}""",
            ),
        )!!
        assertEquals("<user> <message>", remind.usage)
        assertEquals(mapOf("remindme" to "<message>"), remind.aliasUsages)
        assertEquals("Reminds yourself", remind.aliasNotes["remindme"])
        assertEquals(listOf("in:"), remind.parameters)
    }

    /** The list Supibot gives for all commands already gets the guide, before any help is asked. */
    @Test
    fun theListAlreadyKnowsTheArguments() {
        val commands = BotCommandLists.supibot(
            read("""{"data":[{"name":"remind","aliases":["remindme"],"description":"Sets a notification.","flags":[]}]}"""),
        )
        assertEquals("<user> <message>", commands.single().usage)
    }

    @Test
    fun anAliasWithANoteSaysSoFirst() {
        val remind = SupibotGuide.applyTo(BotCommand("remind", listOf("notify", "remindme"), "Sets a notification."))
        val offered = BotCommands.rank("\$remindm", listOf(BotCommands.Offered(Bot.Supibot, "$", listOf(remind))))
        assertEquals("Reminds yourself. Sets a notification.", offered.single().description)
        assertEquals("<message>", offered.single().arguments)
        // An alias without a note is the command under another name.
        assertEquals("Sets a notification.", BotCommands.rank("\$not", listOf(BotCommands.Offered(Bot.Supibot, "$", listOf(remind)))).single().description)
    }

    /** "$fish show" may be followed by a name or by what to show, so both are offered. */
    @Test
    fun anOptionalNameDoesNotHideTheChoicesAfterIt() {
        val fish = SupibotGuide.applyTo(BotCommand("fish"))
        val suggestion = BotCommands.find("\$fish", listOf(BotCommands.Offered(Bot.Supibot, "$", listOf(fish))))!!
        val hint = BotUsage.hint(suggestion, TypedArguments("\$fish", listOf("show"), ""))!!
        assertEquals(listOf("fish", "junk"), hint.choices)
        assertTrue(hint.namesSomeone)
    }

    @Test
    fun aSubcommandIsOfferedAndThenItsArgument() {
        val set = SupibotGuide.applyTo(BotCommand("set", listOf("unset")))
        val suggestion = BotCommands.find("\$set", listOf(BotCommands.Offered(Bot.Supibot, "$", listOf(set))))!!
        val first = BotUsage.hint(suggestion, TypedArguments("\$set", emptyList(), "bi"))!!
        assertEquals(listOf("birthday"), first.choices)
        val then = BotUsage.hint(suggestion, TypedArguments("\$set", listOf("birthday"), ""))!!
        assertEquals("<MM-DD>", then.forms.single().arguments[1].shown)
    }
}
