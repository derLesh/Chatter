package me.lesh.chatter.chat

import me.lesh.chatter.eventsub.EventSubEvent
import me.lesh.chatter.eventsub.HoldReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A message AutoMod holds back until a mod decides. [item] is built for drawing. */
data class HeldMessage(val item: ChatItem, val reason: HoldReason, val heldAt: Long)

/**
 * The messages AutoMod holds in each channel the user moderates, oldest first. Twitch announces
 * each decision, whoever made it, so the queue empties itself. Touched on the chat worker only.
 */
class AutoModQueue(
    private val builder: MessageBuilder,
    private val rooms: Rooms,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _held = MutableStateFlow<Map<String, List<HeldMessage>>>(emptyMap())
    /** Per channel. */
    val held: StateFlow<Map<String, List<HeldMessage>>> = _held

    fun onHeld(event: EventSubEvent.AutoModHeld) {
        val channel = rooms.channelOf(event.channelId) ?: return
        val item = builder.buildFetched(
            channel = channel,
            channelId = event.channelId,
            id = event.messageId,
            login = event.login,
            displayName = event.displayName,
            userId = event.userId.ifEmpty { null },
            text = event.text,
            emotesTag = emotesTag(event.fragments),
            timestamp = event.heldAt,
        ).also { it.body.prepare() }
        val held = HeldMessage(item, event.reason, event.heldAt)
        val oldest = now() - LIFETIME_MS
        _held.update { all ->
            val list = all[channel].orEmpty()
            if (list.any { it.item.id == event.messageId }) return@update all
            all + (channel to (list.filter { it.heldAt > oldest } + held).takeLast(MAX_PER_CHANNEL))
        }
    }

    fun onResolved(event: EventSubEvent.AutoModResolved) {
        val channel = rooms.channelOf(event.channelId) ?: return
        remove(channel, event.messageId)
    }

    /** Takes a message out once the user decided, before Twitch confirms it. */
    fun remove(channel: String, messageId: String) = _held.update { all ->
        val list = all[channel] ?: return@update all
        val rest = list.filterNot { it.item.id == messageId }
        when {
            rest.size == list.size -> all
            rest.isEmpty() -> all - channel
            else -> all + (channel to rest)
        }
    }

    fun forget(channel: String) = _held.update { it - channel }

    fun keepOnly(channels: Set<String>) = _held.update { all -> if (all.keys.all { it in channels }) all else all.filterKeys { it in channels } }

    fun clear() {
        _held.value = emptyMap()
    }

    private companion object {
        /**
         * Twitch lets a held message expire after a few minutes and says so; one whose end was lost
         * with the connection is dropped after this.
         */
        const val LIFETIME_MS = 10 * 60_000L

        /** Enough to work through; a raid of spam should not fill the memory. */
        const val MAX_PER_CHANNEL = 50
    }
}
