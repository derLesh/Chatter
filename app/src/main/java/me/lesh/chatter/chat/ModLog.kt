package me.lesh.chatter.chat

import me.lesh.chatter.eventsub.ModAction
import java.util.UUID

/** How the chat words what a mod did, in the user's language. */
fun interface ModLogNotices {
    /** Null for an action the chat does not mention. */
    fun describe(moderator: String, action: ModAction): String?
}

/**
 * The chat's lines about moderation where the user is a mod. IRC says that someone was timed out,
 * banned or the chat cleared; EventSub says the same with the mod's name and reason, and also what
 * IRC never says, like unbans and mode changes. Which arrives first varies, so the later one of a
 * pair improves or skips the line the earlier one wrote. Touched on the chat worker only.
 */
class ModLog(
    private val buffers: MessageBuffers,
    private val notices: ModLogNotices,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Line(val id: String, val at: Long, val named: Boolean, val seconds: Int?)

    /** By channel and [key]; few, since a pair arrives within a moment. */
    private val recent = LinkedHashMap<String, Line>()

    /** IRC's line about [target] (null: the whole chat), timed out for [seconds] if set. */
    fun fromIrc(channel: String, target: String?, seconds: Int?, text: String) {
        val key = key(channel, target)
        val earlier = fresh(key)
        // EventSub already said it, with the mod's name.
        if (earlier != null && earlier.named) return
        recent[key] = Line(add(channel, text), now(), named = false, seconds = seconds)
    }

    fun fromEventSub(channel: String, moderator: String, action: ModAction) {
        val target = when (action) {
            is ModAction.Ban -> action.target.login
            is ModAction.Timeout -> action.target.login
            ModAction.Clear -> null
            else -> {
                notices.describe(moderator, action)?.let { add(channel, it) }
                return
            }
        }
        val key = key(channel, target)
        val earlier = fresh(key)
        // IRC's duration is exact; EventSub's is worked out from when the timeout ends.
        val exact = if (action is ModAction.Timeout && earlier?.seconds != null) action.copy(seconds = earlier.seconds) else action
        val text = notices.describe(moderator, exact) ?: return
        if (earlier != null && !earlier.named) {
            buffers.update(channel, earlier.id) { it.copy(systemText = text, text = text) }
            recent[key] = Line(earlier.id, earlier.at, named = true, seconds = earlier.seconds)
        } else {
            recent[key] = Line(add(channel, text), now(), named = true, seconds = null)
        }
    }

    fun clear() = recent.clear()

    private fun fresh(key: String): Line? {
        val oldest = now() - PAIR_WINDOW_MS
        recent.values.removeAll { it.at < oldest }
        while (recent.size > MAX_REMEMBERED) recent.remove(recent.keys.first())
        return recent[key]
    }

    private fun key(channel: String, target: String?) = channel + '\u0000' + target?.lowercase().orEmpty()

    private fun add(channel: String, text: String): String {
        val id = UUID.randomUUID().toString()
        buffers.add(
            ChatItem(
                id = id, channel = channel, kind = MessageKind.Notice,
                timestamp = now(), systemText = text, text = text,
            )
        )
        return id
    }

    private companion object {
        /** IRC and EventSub report the same action within about a second of each other. */
        const val PAIR_WINDOW_MS = 10_000L
        const val MAX_REMEMBERED = 64
    }
}
