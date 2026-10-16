package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** A rule tried on a made-up message in the rule editor. */
class RulePreviewTest {
    private fun rule(
        pattern: String,
        id: String = "edited",
        action: RuleAction = RuleAction.Highlight,
        target: RuleTarget = RuleTarget.Message,
        regex: Boolean = false,
        channel: String? = null,
        enabled: Boolean = true,
    ) = ChatRule(id, pattern, enabled, regex, target, action, color = null, channel = channel)

    private fun preview(
        edited: ChatRule,
        text: String = "",
        author: String = "someone",
        channel: String = "forsen",
        saved: List<ChatRule> = emptyList(),
    ) = RulePreview.of(edited, saved, channel, author, text)

    @Test
    fun plainWordsMatchOnlyWholeWords() {
        assertEquals(RuleOutcome.Matches, preview(rule("sub"), "free sub today").outcome)
        assertEquals(RuleOutcome.NoMatch, preview(rule("sub"), "subscribe now").outcome)
    }

    @Test
    fun regexIsUsedAsWritten() {
        val command = rule("^!\\w+", regex = true)
        assertEquals(RuleOutcome.Matches, preview(command, "!uptime").outcome)
        assertEquals(RuleOutcome.NoMatch, preview(command, "that !uptime").outcome)
    }

    @Test
    fun anUnusablePatternIsNotANoMatch() {
        assertEquals(RuleOutcome.Unusable(PatternProblem.Broken), preview(rule("(open", regex = true), "(open").outcome)
        assertEquals(
            RuleOutcome.Unusable(PatternProblem.Unsupported),
            preview(rule("(?=a)a", regex = true), "a").outcome,
        )
        assertEquals(RuleResult.Shown, preview(rule("(open", regex = true), "(open").result)
    }

    @Test
    fun theTargetDecidesWhatIsLookedAt() {
        val byName = rule("nightbot", target = RuleTarget.Author)
        assertEquals(RuleOutcome.Matches, preview(byName, "!commands", author = "Nightbot").outcome)
        assertEquals(RuleOutcome.NoMatch, preview(byName, "nightbot is quiet").outcome)

        val either = rule("nightbot", target = RuleTarget.Any)
        assertEquals(RuleOutcome.Matches, preview(either, "nightbot is quiet").outcome)
        assertEquals(RuleOutcome.Matches, preview(either, "hi", author = "@Nightbot").outcome)
    }

    @Test
    fun aRuleForOneChannelSaysSoForAnother() {
        val forsenOnly = rule("hello", channel = "forsen")
        assertEquals(RuleOutcome.Matches, preview(forsenOnly, "hello", channel = "#Forsen").outcome)
        assertEquals(RuleOutcome.OtherChannel, preview(forsenOnly, "hello", channel = "xqc").outcome)
        assertEquals(RuleResult.Shown, preview(forsenOnly, "hello", channel = "xqc").result)
        assertEquals(RuleOutcome.NoMatch, preview(forsenOnly, "bye", channel = "xqc").outcome)
    }

    @Test
    fun theResultCountsEveryRule() {
        val notify = rule("hello", action = RuleAction.Notify)
        assertEquals(RuleResult.Notifies, preview(notify, "hello there").result)

        val hideBots = rule("bot", id = "bots", target = RuleTarget.Author, action = RuleAction.Hide)
        val fromBot = preview(notify, "hello there", author = "bot", saved = listOf(hideBots))
        assertEquals(RuleOutcome.Matches, fromBot.outcome)
        assertEquals(RuleResult.Hidden, fromBot.result)
    }

    @Test
    fun theEditedRuleReplacesItsSavedVersion() {
        val saved = listOf(rule("hello", action = RuleAction.Hide))
        val edited = rule("hello", action = RuleAction.Highlight)
        assertEquals(RuleResult.Highlighted, preview(edited, "hello", saved = saved).result)
    }

    @Test
    fun aSwitchedOffRuleIsTriedAsIfItWereOn() {
        assertEquals(RuleOutcome.Matches, preview(rule("hello", enabled = false), "hello").outcome)
    }

    @Test
    fun loadedMessagesAreOnlyRead() {
        fun message(id: String, text: String, own: Boolean = false, kind: MessageKind = MessageKind.Chat) =
            ChatItem(id = id, channel = "forsen", kind = kind, timestamp = 0, login = "someone", text = text, isOwn = own)
        val loaded = listOf(
            message("1", "hello"),
            message("2", "bye"),
            message("3", "hello", own = true),
            message("4", "hello", kind = MessageKind.Notice),
            message("5", "well hello"),
        )
        val matches = RulePreview.matchesIn(rule("hello", action = RuleAction.Hide), loaded)
        assertEquals(listOf("1", "5"), matches.map { it.id })
        assertEquals(5, loaded.size)
        assertEquals(emptyList<ChatItem>(), RulePreview.matchesIn(rule("(open", regex = true), loaded))
    }
}
