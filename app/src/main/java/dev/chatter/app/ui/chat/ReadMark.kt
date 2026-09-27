package dev.chatter.app.ui.chat

import androidx.compose.runtime.Immutable
import dev.chatter.app.chat.ChatItem

/**
 * How far a page had been read when the user left it: the newest message they had on screen. The
 * time is kept beside the id for when that message is gone — trimmed away by the limit, or hidden
 * once a moderator deleted it — and only the time can still say what came after it.
 */
@Immutable
data class ReadMark(val id: String, val timestamp: Long)

/**
 * The part of a list that arrived after a [ReadMark]: it starts at [first], oldest first, and is
 * [count] messages long. [cutOff] says that more arrived than the list holds, so the start of it
 * has been trimmed away already.
 */
data class Unseen(val first: Int, val count: Int, val cutOff: Boolean)

/** What of [items] is new since this mark, or null when nothing is. */
fun ReadMark.unseenIn(items: List<ChatItem>): Unseen? {
    val at = items.indexOfLast { it.id == id }
    val first = if (at >= 0) at + 1 else items.indexOfFirst { it.timestamp > timestamp }.let { if (it < 0) items.size else it }
    val count = items.size - first
    if (count == 0) return null
    return Unseen(first, count, cutOff = at < 0 && first == 0)
}
