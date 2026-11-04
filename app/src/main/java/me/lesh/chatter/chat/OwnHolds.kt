package me.lesh.chatter.chat

import me.lesh.chatter.eventsub.HoldStatus
import java.util.UUID

/** How the chat words a decision on the user's held message, in the user's language. */
interface OwnHoldNotices {
    fun allowed(): String
    fun denied(): String
}

/**
 * The user's own messages AutoMod holds. IRC refuses such a message at once and it is struck out;
 * EventSub says later whether a mod let it through. Then the stroke goes and it takes the id Twitch
 * gave it, so a later delete finds it. Touched on the chat worker only.
 */
class OwnHolds(
    private val buffers: MessageBuffers,
    private val notices: OwnHoldNotices,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Twitch's id of a held message to the id it is shown under. */
    private val shownAs = LinkedHashMap<String, String>()

    fun onHeld(channel: String, messageId: String, text: String) {
        val wanted = plain(text)
        val oldest = now() - MATCH_WINDOW_MS
        val own = buffers.findLast(channel) { it.isOwn && it.timestamp >= oldest && plain(it.text) == wanted } ?: return
        shownAs[messageId] = own.id
        if (shownAs.size > MAX_REMEMBERED) shownAs.remove(shownAs.keys.first())
    }

    fun onResolved(channel: String, messageId: String, status: HoldStatus) {
        val shownId = shownAs.remove(messageId)
        when (status) {
            HoldStatus.Approved -> {
                if (shownId != null) {
                    buffers.rename(channel, shownId, messageId)
                    buffers.update(channel, messageId) { it.copy(deleted = false) }
                }
                system(channel, notices.allowed())
            }
            HoldStatus.Denied -> system(channel, notices.denied())
            HoldStatus.Expired, HoldStatus.Invalid -> Unit
        }
    }

    fun clear() = shownAs.clear()

    private fun system(channel: String, text: String) = buffers.add(
        ChatItem(
            id = UUID.randomUUID().toString(), channel = channel, kind = MessageKind.Notice,
            timestamp = now(), systemText = text, text = text,
        )
    )

    private companion object {
        /** Twitch holds a message within moments of it being sent. */
        const val MATCH_WINDOW_MS = 60_000L
        const val MAX_REMEMBERED = 32

        /** The text as both sides have it: without the mark that lets a repeat through, or /me's wrapping. */
        fun plain(text: String) = text.replace(DUPLICATE_MARK, "").removePrefix(ACTION_START).removeSuffix(ACTION_END).trim()

        const val ACTION_START = "\u0001ACTION "
        const val ACTION_END = "\u0001"

        const val DUPLICATE_MARK = "\uDB40\uDC00"
    }
}
