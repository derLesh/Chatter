package dev.chatter.app.chat

import com.google.re2j.Pattern as Re2Pattern

/**
 * Applies the user's [ChatRule]s to every arriving message.
 *
 * Rules run in the user's order; the first hide wins. Highlights and notifies continue, so one rule
 * can color a message and another make it notify. Patterns are compiled once; a broken regex never
 * matches instead of throwing in the middle of the chat.
 *
 * User regexes run over text anyone in chat wrote. A backtracking engine (Android's ICU regex, like
 * java.util.regex) can take minutes on `(a+)+$` with a crafted message and cannot be interrupted,
 * which would stall the message path. So user patterns run on RE2/J, linear in the text length. RE2
 * has no lookarounds or backreferences; such patterns are not used, and the rules page says so
 * ([skips]). Telling safe ones apart by shape does not work: `(a|a)*$` and `(a|aa){1,30}$` blow up
 * just like `(a+)+$`.
 */
class RuleEngine(rules: List<ChatRule> = emptyList()) {
    private class Compiled(val rule: ChatRule, val matcher: TextMatcher)

    private val compiled: List<Compiled> = rules
        .filter { it.enabled && it.pattern.isNotBlank() }
        .mapNotNull { rule -> compile(rule)?.let { Compiled(rule, it) } }

    val isEmpty: Boolean get() = compiled.isEmpty()

    /**
     * The message after the rules, or null if one hides it. The user's own messages are returned
     * unchanged.
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

    /** One compiled pattern, whichever engine runs it. */
    private fun interface TextMatcher {
        fun containsIn(text: String): Boolean
    }

    companion object {
        /** Twitch's message length limit. */
        private const val MAX_TEXT = 500

        /**
         * Whether a regex rule with [pattern] is skipped because RE2 cannot run it (broken, or
         * needs a lookaround or backreference). The rules page shows it.
         */
        fun skips(pattern: String): Boolean = re2(pattern) == null

        private fun re2(pattern: String): Re2Pattern? =
            runCatching { Re2Pattern.compile(pattern, Re2Pattern.CASE_INSENSITIVE) }.getOrNull()

        private fun compile(rule: ChatRule): TextMatcher? {
            if (!rule.regex) {
                // Plain patterns match whole words like a mention, so "sub" does not match
                // "subscribe". Escaped, so nothing in them repeats.
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
