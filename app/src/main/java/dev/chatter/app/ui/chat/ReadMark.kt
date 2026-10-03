package dev.chatter.app.ui.chat

import androidx.compose.runtime.Immutable
import dev.chatter.app.chat.ChatItem

/**
 * How far a page was read when left: the newest message on screen. The time is kept for when that
 * message is gone (trimmed, or hidden after deletion), since only the time still says what came
 * after.
 */
@Immutable
data class ReadMark(val id: String, val timestamp: Long)

/**
 * The part of a list newer than a [ReadMark]: [count] messages from [first], oldest first.
 * [cutOff]: more arrived than the list holds, so the start was already trimmed.
 */
data class Unseen(val first: Int, val count: Int, val cutOff: Boolean)

/** What of [items] is newer than this mark, or null. */
fun ReadMark.unseenIn(items: List<ChatItem>): Unseen? {
    val at = items.indexOfLast { it.id == id }
    val first = if (at >= 0) at + 1 else items.indexOfFirst { it.timestamp > timestamp }.let { if (it < 0) items.size else it }
    val count = items.size - first
    if (count == 0) return null
    return Unseen(first, count, cutOff = at < 0 && first == 0)
}
