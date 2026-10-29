package dev.chatter.app.chat

import android.util.Log
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.auth.TwitchScopes
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixFragment
import dev.chatter.app.net.HelixPinnedMessage
import dev.chatter.app.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Instant

/** A message a moderator pinned above a channel's chat. */
data class PinnedMessage(
    val item: ChatItem,
    val pinnedBy: String,
    /** Epoch millis; null while it stays until the stream ends. */
    val endsAt: Long?,
)

/**
 * The message pinned in each channel the user moderates.
 *
 * Twitch hands pins only to the broadcaster and moderators (`GET chat/pins`) and announces them
 * neither on IRC nor on EventSub, so a channel is asked again every minute while it is on screen.
 */
class PinnedMessages(
    private val helix: HelixApi,
    private val auth: AuthRepository,
    private val rooms: Rooms,
    private val buffers: MessageBuffers,
    private val builder: MessageBuilder,
    /** The chat worker; building a message reads the emote tables, which belong to it. */
    private val worker: CoroutineDispatcher,
) {
    private val _pins = MutableStateFlow<Map<String, PinnedMessage>>(emptyMap())
    /** The pinned message per channel. */
    val pins: StateFlow<Map<String, PinnedMessage>> = _pins

    /** Keeps the pin of [channel] current for as long as the caller lets this run. */
    suspend fun follow(channel: String) {
        while (true) {
            refresh(channel)
            val endsIn = _pins.value[channel]?.endsAt?.let { it - System.currentTimeMillis() }
            delay(endsIn?.coerceIn(0, REFRESH_MS) ?: REFRESH_MS)
        }
    }

    suspend fun refresh(channel: String) {
        val account = auth.account ?: return
        val channelId = rooms.id(channel) ?: return
        if (!TwitchScopes.allows(account.scopes, SCOPE)) return
        val pin = try {
            helix.pinnedMessage(channelId, account.userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            // No longer a moderator there: the pin is not the user's to see any more.
            if (e.code == 401 || e.code == 403) forget(channel)
            Log.d(TAG, "No pin for $channel: ${e.message}")
            return
        } catch (e: Exception) {
            Log.d(TAG, "No pin for $channel: ${e.message}")
            return
        }
        val now = System.currentTimeMillis()
        val pinned = pin?.let { toPinned(channel, channelId, it) }?.takeIf { (it.endsAt ?: Long.MAX_VALUE) > now }
        _pins.update { if (pinned == null) it - channel else it + (channel to pinned) }
    }

    /**
     * Swaps a pin built from Helix for the message in the chat, with its badges and color. At start
     * the pin often arrives before the history that holds it.
     */
    suspend fun takeFromChat(channel: String) {
        val pin = _pins.value[channel] ?: return
        val inChat = buffers.find(channel, pin.item.id)?.takeIf { it !== pin.item } ?: return
        _pins.update { pins -> if (pins[channel] == pin) pins + (channel to pin.copy(item = inChat)) else pins }
    }

    fun forget(channel: String) = _pins.update { it - channel }

    fun clear() {
        _pins.value = emptyMap()
    }

    /** The message as the chat shows it; built from the pin once it left the chat. */
    private suspend fun toPinned(channel: String, channelId: String, pin: HelixPinnedMessage): PinnedMessage {
        val item = buffers.find(channel, pin.messageId) ?: withContext(worker) {
            // The user's own badges and color are known here even without the message.
            val own = pin.senderLogin.equals(auth.account?.login, ignoreCase = true)
            val ownState = if (own) rooms.userState(channel) else null
            builder.buildFetched(
                channel = channel,
                channelId = channelId,
                id = pin.messageId,
                login = pin.senderLogin,
                displayName = pin.senderName.ifEmpty { pin.senderLogin },
                userId = pin.senderId.ifEmpty { null },
                text = pin.message.text,
                emotesTag = emotesTag(pin.message.fragments),
                timestamp = parseTime(pin.startsAt) ?: System.currentTimeMillis(),
                badgesTag = ownState?.get("badges"),
                color = MessageBuilder.parseColor(ownState?.get("color")),
            ).also { it.body.prepare() }
        }
        return PinnedMessage(item, pin.pinnedByName, parseTime(pin.endsAt))
    }

    private fun parseTime(rfc3339: String?): Long? =
        rfc3339?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private companion object {
        const val TAG = "PinnedMessages"
        const val REFRESH_MS = 60_000L

        /** Moderators who can manage pins may read them too. */
        const val SCOPE = "moderator:manage:chat_messages"
    }
}

/**
 * Helix's message fragments as an IRC `emotes` tag ("25:0-4,12-16/1902:6-10"), so the builder reads
 * them like any other message. Positions count code points, as on IRC.
 */
internal fun emotesTag(fragments: List<HelixFragment>): String? {
    val ranges = LinkedHashMap<String, MutableList<String>>()
    var position = 0
    for (fragment in fragments) {
        val length = fragment.text.codePointCount(0, fragment.text.length)
        val id = fragment.emote?.id
        if (fragment.type == "emote" && id != null && length > 0) {
            ranges.getOrPut(id) { ArrayList() } += "$position-${position + length - 1}"
        }
        position += length
    }
    return ranges.entries.joinToString("/") { (id, at) -> "$id:${at.joinToString(",")}" }.ifEmpty { null }
}
