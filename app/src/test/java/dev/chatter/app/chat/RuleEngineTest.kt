package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private var nextId = 0

    private fun rule(
        pattern: String,
        action: RuleAction = RuleAction.Highlight,
        target: RuleTarget = RuleTarget.Message,
        regex: Boolean = false,
        color: Int? = null,
        channel: String? = null,
        enabled: Boolean = true,
    ) = ChatRule("${nextId++}", pattern, enabled, regex, target, action, color, channel)

    private fun message(
        text: String = "",
        login: String = "someone",
        channel: String = "forsen",
        isOwn: Boolean = false,
    ) = ChatItem(
        id = "id-$text", channel = channel, kind = MessageKind.Chat, timestamp = 0,
        login = login, displayName = login.replaceFirstChar { it.uppercase() }, text = text, isOwn = isOwn,
    )

    @Test
    fun plainPatternsMatchWholeWords() {
        val engine = RuleEngine(listOf(rule("sub", color = RED)))
        assertEquals(RED, engine.apply(message("free sub today"))?.highlight)
        assertNull(engine.apply(message("subscribe now"))?.highlight)
    }

    @Test
    fun regexPatternsAreUsedAsWritten() {
        val engine = RuleEngine(listOf(rule("^!\\w+", regex = true, color = RED)))
        assertEquals(RED, engine.apply(message("!uptime"))?.highlight)
        assertNull(engine.apply(message("that !uptime thing"))?.highlight)
    }

    @Test
    fun brokenRegexNeverMatches() {
        val engine = RuleEngine(listOf(rule("(unclosed", regex = true, color = RED)))
        assertTrue(engine.isEmpty)
        assertNull(engine.apply(message("(unclosed"))?.highlight)
    }

    @Test
    fun authorRulesLookAtTheName() {
        val engine = RuleEngine(listOf(rule("nightbot", target = RuleTarget.Author, action = RuleAction.Hide)))
        assertNull(engine.apply(message("!commands", login = "nightbot")))
        assertEquals("nightbot is quiet", engine.apply(message("nightbot is quiet", login = "someone"))?.text)
    }

    @Test
    fun anyLooksAtBoth() {
        val engine = RuleEngine(listOf(rule("bot", target = RuleTarget.Any, color = RED)))
        assertEquals(RED, engine.apply(message("hi", login = "bot"))?.highlight)
        assertEquals(RED, engine.apply(message("that bot again"))?.highlight)
    }

    @Test
    fun notifyTurnsAMessageIntoAMention() {
        val engine = RuleEngine(listOf(rule("giveaway", action = RuleAction.Notify)))
        val item = engine.apply(message("giveaway starting"))
        assertTrue(item!!.isMention)
        // Without a color of its own the message keeps the mention highlight from the settings.
        assertNull(item.highlight)
    }

    @Test
    fun hideWins() {
        val engine = RuleEngine(listOf(rule("spam", color = RED), rule("spam", action = RuleAction.Hide)))
        assertNull(engine.apply(message("spam spam")))
    }

    @Test
    fun rulesCanBeLimitedToOneChannel() {
        val engine = RuleEngine(listOf(rule("drop", action = RuleAction.Hide, channel = "forsen")))
        assertNull(engine.apply(message("drop", channel = "forsen")))
        assertEquals("drop", engine.apply(message("drop", channel = "xqc"))?.text)
    }

    @Test
    fun disabledRulesAndOwnMessagesAreLeftAlone() {
        val engine = RuleEngine(listOf(rule("hi", action = RuleAction.Hide, enabled = false)))
        assertTrue(engine.isEmpty)

        val hiding = RuleEngine(listOf(rule("hi", action = RuleAction.Hide)))
        val own = message("hi", isOwn = true)
        assertSame(own, hiding.apply(own))
    }

    /**
     * `(a+)+$` against a long run of a's that does not end the way it wants takes a backtracking
     * engine longer than anybody would wait. On RE2 it is as quick as any other pattern.
     */
    @Test
    fun aPatternThatWouldBacktrackRunsInLinearTime() {
        val engine = RuleEngine(listOf(rule("(a+)+$", regex = true, color = RED)))
        val started = System.nanoTime()
        assertNull(engine.apply(message("a".repeat(400) + "!"))?.highlight)
        assertEquals(RED, engine.apply(message("aaaa"))?.highlight)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 1_000)
        assertFalse(RuleEngine.skips("(a+)+$"))
    }

    /**
     * The shapes no heuristic catches — an alternation that matches the same text two ways, a
     * bounded repeat of one — run on RE2 as well, and finish.
     */
    @Test
    fun ambiguousRepetitionRunsInLinearTimeToo() {
        listOf("(a|a)*$", "(a|aa){1,30}$", "(.*a){12}").forEach { pattern ->
            val engine = RuleEngine(listOf(rule(pattern, regex = true, color = RED)))
            val started = System.nanoTime()
            engine.apply(message("a".repeat(400) + "!"))
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue("$pattern took $millis ms", millis < 1_000)
        }
    }

    /**
     * RE2 has no lookarounds or backreferences, and no other engine can be stopped once it runs
     * away, so a pattern that needs them is not used at all — whatever its shape.
     */
    @Test
    fun aPatternRE2CannotRunIsNotUsed() {
        listOf("(?<!@)lesh", """(?=x)(\w+\s?)*$""", """(a)\1""", "(unclosed").forEach { pattern ->
            assertTrue(pattern, RuleEngine.skips(pattern))
            assertTrue(pattern, RuleEngine(listOf(rule(pattern, regex = true, color = RED))).isEmpty)
        }
    }

    @Test
    fun ordinaryPatternsAreUsed() {
        assertFalse(RuleEngine.skips("""^!\w+"""))
        assertFalse(RuleEngine.skips("(foo|bar)+"))
        assertFalse(RuleEngine.skips("""\bgiveaway\b"""))
    }

    /** Plain words keep matching whole words, which is not a regex the user wrote. */
    @Test
    fun plainWordsAreNotAffected() {
        val engine = RuleEngine(listOf(rule("(?<!@)lesh", color = RED)))
        assertEquals(RED, engine.apply(message("look (?<!@)lesh here"))?.highlight)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
