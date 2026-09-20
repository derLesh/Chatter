package dev.chatter.app.chat

/**
 * Runs the user's [ChatRule]s over every arriving message.
 *
 * Rules are checked in the order the user put them in, and the first hide wins — after that
 * there is no message left to paint. Highlights and notifies keep going, so a message can be
 * colored by one rule and made to notify by another.
 *
 * Patterns are compiled once, here: a broken regular expression simply never matches instead of
 * throwing somewhere in the middle of the chat.
 */
class RuleEngine(rules: List<ChatRule> = emptyList()) {
    private class Compiled(val rule: ChatRule, val regex: Regex)

    private val compiled: List<Compiled> = rules
        .filter { it.enabled && it.pattern.isNotBlank() }
        .mapNotNull { rule -> compile(rule)?.let { Compiled(rule, it) } }

    val isEmpty: Boolean get() = compiled.isEmpty()

    /**
     * The message as the rules leave it, or null if one of them hides it. The user's own
     * messages are handed back untouched: rules are about what others write.
     */
    fun apply(item: ChatItem): ChatItem? {
        if (compiled.isEmpty() || item.isOwn) return item
        var result = item
        for (c in compiled) {
            if (c.rule.channel != null && !c.rule.channel.equals(item.channel, ignoreCase = true)) continue
            if (!matches(c, item)) continue
            when (c.rule.action) {
                RuleAction.Hide -> return null
                RuleAction.Highlight -> result = result.copy(highlight = c.rule.color ?: result.highlight)
                RuleAction.Notify -> result = result.copy(
                    isMention = true,
                    highlight = c.rule.color ?: result.highlight,
                )
            }
        }
        return result
    }

    private fun matches(c: Compiled, item: ChatItem): Boolean {
        val author = { c.regex.containsMatchIn(item.login.orEmpty()) || c.regex.containsMatchIn(item.displayName.orEmpty()) }
        return when (c.rule.target) {
            RuleTarget.Message -> c.regex.containsMatchIn(item.text)
            RuleTarget.Author -> author()
            RuleTarget.Any -> c.regex.containsMatchIn(item.text) || author()
        }
    }

    private companion object {
        fun compile(rule: ChatRule): Regex? = runCatching {
            // Plain patterns match whole words, exactly like a mention does, so a rule for "sub"
            // does not fire on every "subscribe".
            val body = if (rule.regex) rule.pattern else {
                "(?<![\\p{L}\\p{N}_])(?:${Regex.escape(rule.pattern.trim())})(?![\\p{L}\\p{N}_])"
            }
            Regex(body, RegexOption.IGNORE_CASE)
        }.getOrNull()
    }
}
