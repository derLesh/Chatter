package dev.chatter.app.channels

import kotlinx.serialization.Serializable

/**
 * Several channels read as one chat: their messages side by side on a page of their own, each
 * marked with the picture of the channel it was written in.
 *
 * The channels stay channels of their own as well. A combined chat only reads from them, so taking
 * one apart again loses nothing.
 *
 * [name] is empty until the user picks one; the chat is then called after its channels, which
 * keeps following them when one is renamed.
 */
@Serializable
data class ChannelGroup(
    val id: String,
    val name: String = "",
    val channels: List<String> = emptyList(),
) {
    /** What the combined chat is listed under among the channels — see [isKey]. */
    val key: String get() = PREFIX + id

    companion object {
        /**
         * Marks a combined chat in the list of pages. Twitch logins are letters, digits and
         * underscores only, so nothing starting with it can ever be taken for a channel.
         */
        const val PREFIX = "+"

        /** Whether a page of the chat is a combined chat rather than a single channel. */
        fun isKey(page: String): Boolean = page.startsWith(PREFIX)

        fun idOf(key: String): String = key.removePrefix(PREFIX)
    }
}

/** The name a combined chat is shown under: the user's, or its channels' in a row. */
fun ChannelGroup.displayName(info: Map<String, ChannelInfo>): String =
    name.ifBlank { channels.joinToString(", ") { info[it]?.displayName ?: it } }
