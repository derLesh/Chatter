package dev.chatter.app.chat

import android.content.Context
import dev.chatter.app.R

/** The real [ChatNotices]: the same lines, in whichever language the phone is set to. */
class AppChatNotices(private val context: Context) : ChatNotices {
    override fun chatCleared(): String = context.getString(R.string.chat_cleared)

    override fun timeout(name: String, seconds: Int): String =
        context.resources.getQuantityString(R.plurals.chat_timeout, seconds, name, seconds)

    override fun ban(name: String): String = context.getString(R.string.chat_ban, name)
}
