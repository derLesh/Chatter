package dev.chatter.app.chat

import com.google.re2j.Pattern as Re2Pattern

/**
 * Runs the user's [ChatRule]s over every arriving message.
 *
 * Rules are checked in the order the user put them in, and the first hide wins — after that
 * there is no message left to paint. Highlights and notifies keep going, so a message can be
 * colored by one rule and made to notify by another.
 *
 * Patterns are compiled once, here: a broken regular expression simply never matches instead of
 * throwing somewhere in the middle of the chat.
 *
 * A regular expression is the user's, but the text it runs over is whatever anybody in chat
 * wrote. A backtracking engine — Android's ICU regex, like java.util.regex — can take minutes on
 * a pattern like `(a+)+$` and one message made for it, and it cannot be stopped from outside: it
 * copies the text and runs natively. That would hold the message path all night in the
 * background. So user patterns run on RE2/J, whose time grows only with the length of the text,
 * whatever the pattern — and only there. RE2 knows no lookarounds or backreferences; a pattern
 * that needs them is not used rather than handed to an engine that may never finish, and the
 * rules page says so ([skips]). Telling the safe ones of those apart from the rest by their
 * shape does not work: `(a|a)*$` and `(a|aa){1,30}$` run away just as `(a+)+$` does.
 */
class RuleEngine(rules: List<ChatRule> = emptyList()) {
    private class Compiled(val rule: ChatRule, val matcher: TextMatcher)

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
        val m = c.matcher
        val author = { m.containsIn(item.login.orEmpty()) || m.containsIn(item.displayName.orEmpty()) }
        return when (c.rule.target) {
            RuleTarget.Message -> m.containsIn(item.text)
            RuleTarget.Author -> author()
            RuleTarget.Any -> m.containsIn(item.text) || author()
        }
    }

    /** One compiled pattern, whichever engine it runs on. */
    private fun interface TextMatcher {
        fun containsIn(text: String): Boolean
    }

    companion object {
        /** Twitch's own limit for a message; nothing longer is worth reading. */
        private const val MAX_TEXT = 500

        /**
         * Whether a regular-expression rule with [pattern] is not used: RE2 cannot run it,
         * because it is broken or needs a lookaround or a backreference. The rules page says so.
         */
        fun skips(pattern: String): Boolean = re2(pattern) == null

        private fun re2(pattern: String): Re2Pattern? =
            runCatching { Re2Pattern.compile(pattern, Re2Pattern.CASE_INSENSITIVE) }.getOrNull()

        private fun compile(rule: ChatRule): TextMatcher? {
            if (!rule.regex) {
                // Plain patterns match whole words, exactly like a mention does, so a rule for
                // "sub" does not fire on every "subscribe". Escaped, so nothing in it repeats.
                val word = Regex(
                    "(?<![\\p{L}\\p{N}_])(?:${Regex.escape(rule.pattern.trim())})(?![\\p{L}\\p{N}_])",
                    RegexOption.IGNORE_CASE,
                )
                return TextMatcher { word.containsMatchIn(it.take(MAX_TEXT)) }
            }
            val p = re2(rule.pattern) ?: return null
            return TextMatcher { p.matcher(it.take(MAX_TEXT)).find() }
        }
    }
}
