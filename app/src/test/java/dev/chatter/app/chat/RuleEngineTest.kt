package dev.chatter.app.chat

import org.junit.Assert.assertEquals
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

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
