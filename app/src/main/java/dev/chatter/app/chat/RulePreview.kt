package dev.chatter.app.chat

/** Whether a rule being written acts on a message, before it is saved. */
sealed interface RuleOutcome {
    /** The regex cannot be used, so the rule acts on no message at all. */
    data class Unusable(val problem: PatternProblem) : RuleOutcome

    /** The message does not have what the rule looks for. */
    data object NoMatch : RuleOutcome

    /** The message has it, but was written in another channel than the one the rule is for. */
    data object OtherChannel : RuleOutcome

    data object Matches : RuleOutcome
}

/** What happens to a message once every rule ran over it. */
enum class RuleResult { Shown, Highlighted, Notifies, Hidden }

/**
 * A rule tried on a made-up message, with the same [RuleEngine] the chat uses. [outcome] is the rule
 * alone; [result] is all rules together, the edited one in place of its saved version.
 */
class RulePreview(val outcome: RuleOutcome, val result: RuleResult) {
    companion object {
        /**
         * Tries [edited] on a message [author] wrote in [channel]. [saved] are the user's rules; a
         * new rule goes last, as saving puts it there. The rule is tried as if it were on.
         */
        fun of(edited: ChatRule, saved: List<ChatRule>, channel: String, author: String, text: String): RulePreview {
            val rule = edited.copy(pattern = edited.pattern.trim(), enabled = true)
            val message = sample(channel, author, text)
            val problem = if (rule.regex) RuleEngine.problem(rule.pattern) else null
            val outcome = when {
                problem != null -> RuleOutcome.Unusable(problem)
                RuleEngine(listOf(rule.copy(channel = null))).matching(message).isEmpty() -> RuleOutcome.NoMatch
                RuleEngine(listOf(rule)).matching(message).isEmpty() -> RuleOutcome.OtherChannel
                else -> RuleOutcome.Matches
            }
            val all = if (saved.any { it.id == rule.id }) saved.map { if (it.id == rule.id) rule else it } else saved + rule
            val after = RuleEngine(all).apply(message)
            val result = when {
                after == null -> RuleResult.Hidden
                after.isMention -> RuleResult.Notifies
                after.highlight != null -> RuleResult.Highlighted
                else -> RuleResult.Shown
            }
            return RulePreview(outcome, result)
        }

        /**
         * The messages among [loaded] that [edited] acts on, newest last. Only what other people
         * wrote: rules leave the user's own messages alone.
         */
        fun matchesIn(edited: ChatRule, loaded: List<ChatItem>): List<ChatItem> {
            val rule = edited.copy(pattern = edited.pattern.trim(), enabled = true)
            val engine = RuleEngine(listOf(rule))
            if (engine.isEmpty) return emptyList()
            return loaded.filter { !it.isOwn && it.canReply && engine.matching(it).isNotEmpty() }
        }

        private fun sample(channel: String, author: String, text: String): ChatItem {
            val name = author.trim().removePrefix("@")
            return ChatItem(
                id = "rule-preview",
                channel = channel.trim().removePrefix("#").lowercase(),
                kind = MessageKind.Chat,
                timestamp = 0,
                login = name.lowercase(),
                displayName = name,
                text = text,
            )
        }
    }
}
