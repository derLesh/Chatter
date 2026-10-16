package dev.chatter.app.ui

import androidx.compose.ui.text.input.TextFieldValue
import dev.chatter.app.chat.ChatItem

/** Unsent input on a page: the text and the message it answers. */
internal data class Draft(val input: TextFieldValue, val replyTo: ChatItem?) {
    val isEmpty: Boolean get() = input.text.isBlank() && replyTo == null

    /**
     * This draft once [sent] went out. Sending takes a moment, so only what the user left as it was
     * is cleared; anything written or answered since stays.
     */
    fun afterSending(sent: Draft): Draft = Draft(
        input = if (input.text == sent.input.text) TextFieldValue("") else input,
        replyTo = replyTo.takeUnless { it == sent.replyTo },
    )
}
