package me.lesh.chatter.chat

import me.lesh.chatter.stats.TalkedTo

/** Who a message the user sent speaks to: the chatter it answers and everyone it @mentions. */
object Addressees {
    /** Each chatter once, never [self]. */
    fun of(message: ChatItem, self: String): List<TalkedTo> {
        val mentioned = message.segments.filterIsInstance<Segment.Mention>().mapNotNull { mention ->
            // The segment holds the word as typed, "@Name," included.
            val name = mention.name.removePrefix("@").trimEnd { !it.isLetterOrDigit() && it != '_' }
            name.takeIf { LOGIN.matches(it) }?.let { TalkedTo(it.lowercase(), it) }
        }
        val answered = message.reply?.takeIf { it.parentLogin.isNotEmpty() }?.let {
            TalkedTo(it.parentLogin.lowercase(), it.parentDisplayName.ifEmpty { it.parentLogin })
        }
        return (listOfNotNull(answered) + mentioned)
            .distinctBy { it.login }
            .filter { !it.login.equals(self, ignoreCase = true) }
    }

    /** Twitch's rules for a login, so "@" alone or an e-mail address is not taken for a chatter. */
    private val LOGIN = Regex("[A-Za-z0-9_]{3,25}")
}
