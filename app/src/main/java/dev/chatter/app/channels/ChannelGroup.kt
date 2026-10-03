package dev.chatter.app.channels

import kotlinx.serialization.Serializable

/**
 * Several channels read as one chat, each message marked with the picture of its channel. The
 * channels stay in the list on their own as well.
 *
 * [name] is empty until the user picks one; the chat is then named after its channels.
 */
@Serializable
data class ChannelGroup(
    val id: String,
    val name: String = "",
    val channels: List<String> = emptyList(),
) {
    /** Its entry in the page list; see [isKey]. */
    val key: String get() = PREFIX + id

    companion object {
        /** Marks a combined chat in the page list. Twitch logins cannot contain it. */
        const val PREFIX = "+"

        /** Whether [page] is a combined chat rather than a channel. */
        fun isKey(page: String): Boolean = page.startsWith(PREFIX)

        fun idOf(key: String): String = key.removePrefix(PREFIX)

        /**
         * Letters, digits and dashes, as made from a UUID. Ids from a backup are checked as well:
         * the key goes into the comma-separated page list, where a comma would split off a name
         * that is then joined over IRC.
         */
        private val ID = Regex("^[A-Za-z0-9-]{1,36}$")

        fun isValidId(id: String): Boolean = ID.matches(id)
    }
}

/** The user's name for it, or the names of its channels in a row. */
fun ChannelGroup.displayName(info: Map<String, ChannelInfo>): String =
    name.ifBlank { channels.joinToString(", ") { info[it]?.displayName ?: it } }
