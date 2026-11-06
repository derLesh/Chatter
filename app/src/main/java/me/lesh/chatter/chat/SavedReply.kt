package me.lesh.chatter.chat

import kotlinx.serialization.Serializable

/** An answered message as text: only what the reply strip and Twitch need. */
@Serializable
class SavedReply(
    val id: String,
    val channel: String,
    val timestamp: Long,
    val login: String? = null,
    val displayName: String? = null,
    val text: String = "",
    val action: Boolean = false,
    /** The conversation the answered message is part of, if it answers another itself. */
    val threadId: String? = null,
) {
    /** The answered message without what only drawing needs; the quote is built again from [text]. */
    fun toItem() = ChatItem(
        id = id,
        channel = channel,
        kind = if (action) MessageKind.Action else MessageKind.Chat,
        timestamp = timestamp,
        login = login,
        displayName = displayName,
        text = text,
        reply = threadId?.let { ReplyInfo(parentId = it, parentLogin = "", parentDisplayName = "", parentBody = "", threadId = it) },
    )

    companion object {
        fun of(item: ChatItem) = SavedReply(
            id = item.id,
            channel = item.channel,
            timestamp = item.timestamp,
            login = item.login,
            displayName = item.displayName,
            text = item.text,
            action = item.kind == MessageKind.Action,
            threadId = item.reply?.threadId,
        )
    }
}
