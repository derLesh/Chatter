package me.lesh.chatter.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the bots' usage texts are read, and what the input offers while a command's arguments are typed. */
class BotUsageTest {
    // ---- Reading a usage ---------------------------------------------------------

    @Test
    fun theCommandIsTakenOffEachWayOfWritingIt() {
        assertEquals("<join|leave> <name> | list", BotUsage.withoutCommand("!p <join|leave> <name> | !p list"))
        assertEquals("<ICAO-Typ> <dauer>", BotUsage.withoutCommand("!fl <ICAO-Typ> <dauer>"))
        assertEquals("", BotUsage.withoutCommand("!v"))
        assertEquals("", BotUsage.withoutCommand(""))
    }

    /** PotatBot writes "or" between the ways, and some of its names stand for two commands. */
    @Test
    fun potatBotsUsageIsReadToo() {
        assertEquals("@<user> | <badge>", BotUsage.withoutCommand("#badge @<user> or <badge>"))
        assertEquals("<emote(s)>", BotUsage.withoutCommand("#pinemote/unpinemote <emote(s)>"))
        assertEquals("<channel time> to:<timezone>", BotUsage.withoutCommand("#localtime <channel time> to:<timezone>"))
    }

    @Test
    fun anArgumentWithSpacesStaysOneArgument() {
        assertEquals(listOf(listOf("<Name, ID oder 7TV-Link>")), BotUsage.forms("<Name, ID oder 7TV-Link>").shown())
    }

    @Test
    fun choicesAreTheWordsAnArgumentCanBe() {
        val (choice, name, place, fixed, onOff) = BotUsage.forms("<join|leave> <name> <PLZ/ICAO/IATA/Ort> list on/off").single()
        assertEquals(listOf("join", "leave"), choice.choices)
        assertEquals(emptyList<String>(), name.choices)
        // Kinds of things, not words to type.
        assertEquals(emptyList<String>(), place.choices)
        assertEquals(listOf("list"), fixed.choices)
        assertEquals(listOf("on", "off"), onOff.choices)
        assertEquals(emptyList<String>(), BotUsage.forms("<timezone|@user>").single().single().choices)
    }

    @Test
    fun anArgumentForSomeoneIsKnownByItsName() {
        listOf("[user]", "<username>", "@<user>", "<person>", "<nutzer>").forEach {
            assertTrue(it, BotUsage.forms(it).single().single().namesSomeone)
        }
        listOf("<name>", "<users-only>x", "<query>").forEach { assertFalse(it, BotUsage.forms(it).single().single().namesSomeone) }
    }

    // ---- Supibot's examples ------------------------------------------------------

    @Test
    fun supibotsExamplesBecomeWaysOfWritingTheCommand() {
        val examples = listOf(
            "\$weather (place)",
            "\$weather Berlin",
            "\$weather Norway, Michigan, US",
            "\$weather (place) alerts:true",
            "\$weather (place) hour+X",
            "\$weather provider:(provider)",
            "\$set location",
        )
        assertEquals("<place>", BotUsage.fromExamples(examples, listOf("\$weather"), listOf("alerts", "provider")))
    }

    @Test
    fun aSubcommandIsKeptButASentenceIsNot() {
        val examples = listOf(
            "\$cookie",
            "\$cookie eat",
            "\$cookie donate (user)",
            "\$cookie stats (user)",
            "\$pick a b c",
            "\$gpt What should I eat today?",
            "\$cookie @Supinic",
        )
        assertEquals("eat | donate <user> | stats <user>", BotUsage.fromExamples(examples, listOf("\$cookie"), emptyList()))
        assertEquals("", BotUsage.fromExamples(examples, listOf("\$pick", "\$gpt"), emptyList()))
    }

    /** "test" and "hello :)" stand for the message the user writes, not for words to type. */
    @Test
    fun wordsAfterAnArgumentAreTheUsersText() {
        val examples = listOf(
            "\$remind (person) hello :)",
            "\$notify (person) hello :)",
            "\$remind (person) test on:8pm",
            "\$remind (person) hi :) in:(time)!",
            "\$remind (person) hello :) in (time)",
            "\$remindme test (time)",
            "\$remind supinic test in:1hr",
        )
        val names = listOf("\$remind", "\$notify", "\$remindme")
        assertEquals("<person> <text>", BotUsage.fromExamples(examples, names, listOf("in", "on")))
    }

    /** For an alias alone, a word before its first argument is not taken for a subcommand. */
    @Test
    fun anAliasGetsOnlyWhatItsOwnExamplesShow() {
        val examples = listOf("\$remindme test (time)", "\$notify (person) hello :)")
        assertEquals("", BotUsage.fromExamples(examples, listOf("\$remindme"), emptyList(), subcommands = false))
        assertEquals("<person> <text>", BotUsage.fromExamples(examples, listOf("\$notify"), emptyList(), subcommands = false))
    }

    @Test
    fun anAliasCountsAndAQuotedParameterIsLeftOut() {
        val examples = listOf("\$poop (status)", "\$afk (status)", "\$osrs kc activity:\"(activity name)\" (username)")
        assertEquals("<status>", BotUsage.fromExamples(examples, listOf("\$afk", "\$poop"), emptyList()))
        assertEquals("kc <username>", BotUsage.fromExamples(examples, listOf("\$osrs"), listOf("activity")))
    }

    // ---- Where the cursor is ---------------------------------------------------------

    /** Supibot takes values in quotes, like on:"tomorrow 10am", spaces and all. */
    @Test
    fun aQuotedValueIsOneWord() {
        val text = "\$remind on:\"tomorrow 10am\" "
        assertEquals(TypedArguments("\$remind", listOf("on:\"tomorrow 10am\""), ""), BotUsage.typed(text, text.length))
        val open = "\$remind on:\"tomorrow 10"
        assertEquals(TypedArguments("\$remind", emptyList(), "on:\"tomorrow 10"), BotUsage.typed(open, open.length))
        // So the user is still the one to write.
        val remind = BotSuggestion(Bot.Supibot, "\$remind", "", "<user> <message>", listOf("on:"))
        assertTrue(BotUsage.hint(remind, BotUsage.typed(text, text.length)!!)!!.namesSomeone)
    }

    @Test
    fun whatFollowsACommandIsRead() {
        assertEquals(TypedArguments("!fl", emptyList(), ""), BotUsage.typed("!fl ", 4))
        assertEquals(TypedArguments("!fl", listOf("A320"), "2"), BotUsage.typed("!fl A320 2h", 10))
        assertEquals(TypedArguments("!fl", listOf("A320"), ""), BotUsage.typed("!fl A320  ", 10))
        // Still in the command, or no command at all.
        assertNull(BotUsage.typed("!fl", 3))
        assertNull(BotUsage.typed("!fl A320", 2))
        assertNull(BotUsage.typed("hello there", 8))
        assertNull(BotUsage.typed("/ban someone", 8))
    }

    @Test
    fun theArgumentBeingTypedIsPickedOut() {
        val hint = hint(BotSuggestion(Bot.ChronophylosBot, "!fl", "Flugplan", "<ICAO-Typ> <dauer>"), "!fl A320 ")
        assertEquals(listOf(UsageForm(BotUsage.forms("<ICAO-Typ> <dauer>").single(), 1)), hint?.forms)
        assertEquals("Flugplan", hint?.description)
    }

    @Test
    fun aMessageAtTheEndTakesEveryWordThatFollows() {
        val hint = hint(BotSuggestion(Bot.ChronophylosBot, "!fb", "", "<nachricht>"), "!fb this is great ")
        assertEquals(0, hint?.forms?.single()?.current)
    }

    @Test
    fun theWaysThatStillFitOfferTheirChoices() {
        val command = BotSuggestion(Bot.ChronophylosBot, "!p", "", "<join|leave> <name> | list")
        assertEquals(listOf("join", "leave", "list"), hint(command, "!p ")?.choices)
        assertEquals(listOf("leave", "list"), hint(command, "!p l")?.choices)

        val joining = hint(command, "!p join ")
        assertEquals(1, joining?.forms?.size)
        assertEquals(1, joining?.forms?.single()?.current)
        assertEquals(emptyList<String>(), joining?.choices)
    }

    @Test
    fun aWordThatFitsNoWayStillShowsThemAll() {
        val hint = hint(BotSuggestion(Bot.ChronophylosBot, "!p", "", "<join|leave> <name> | list"), "!p dance ")
        assertEquals(listOf<Int?>(null, null), hint?.forms?.map { it.current })
    }

    @Test
    fun aUserArgumentAsksForNames() {
        assertTrue(hint(BotSuggestion(Bot.ChronophylosBot, "!pw", "", "[user]"), "!pw ")!!.namesSomeone)
    }

    @Test
    fun namedParametersAreOfferedUntilUsed() {
        val weather = BotSuggestion(Bot.Supibot, "\$weather", "", "<place>", listOf("alerts:true", "format:"))
        assertEquals(listOf("alerts:true", "format:"), hint(weather, "\$weather ")?.parameters)
        assertEquals(listOf("format:"), hint(weather, "\$weather alerts:true ")?.parameters)
        assertEquals(listOf("format:"), hint(weather, "\$weather fo")?.parameters)
        // A parameter does not count as the place.
        assertEquals(0, hint(weather, "\$weather alerts:true ")?.forms?.single()?.current)
        // What was just put in is not offered again.
        assertEquals(emptyList<String>(), hint(weather, "\$weather format:")?.parameters)
    }

    @Test
    fun aCommandWithoutArgumentsHasNoHint() {
        assertNull(hint(BotSuggestion(Bot.ChronophylosBot, "!v", ""), "!v "))
    }

    private fun hint(command: BotSuggestion, text: String) = BotUsage.hint(command, BotUsage.typed(text, text.length)!!)

    private fun List<List<BotArgument>>.shown() = map { form -> form.map { it.shown } }
}
