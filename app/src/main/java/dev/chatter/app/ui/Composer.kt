package dev.chatter.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.CommandParser
import dev.chatter.app.chat.MessageKind
import dev.chatter.app.chat.ReplyInfo
import dev.chatter.app.chat.SendResult
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What a [Composer] needs from the chat to send. */
internal interface Outbox {
    suspend fun send(channel: String, text: String, replyTo: ChatItem?): SendResult

    /** How many seconds slow mode makes the user wait in [channel] after writing there. */
    fun slowSeconds(channel: String): Int

    /** Tells the user why a message did not go out. */
    suspend fun report(result: SendResult)
}

/**
 * What one window is writing: the input, the message it answers, the drafts of the pages not on
 * screen and the channel a message goes to. Drafts belong to the account they were written as, and
 * [save] keeps them for when Android ends the process in the background.
 *
 * [onInputReplaced] is called when the input changes without the user typing, after a send or a
 * refusal, so suggestions can follow.
 */
internal class Composer(
    private val scope: CoroutineScope,
    private val outbox: Outbox,
    private val onInputReplaced: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    var input by mutableStateOf(TextFieldValue(""))
    var replyTo by mutableStateOf<ChatItem?>(null)
        private set

    /**
     * Number of replies started. The input opens the keyboard for each new one, but not for a reply
     * restored with its page's draft.
     */
    var replyStarts by mutableIntStateOf(0)
        private set

    /** Pages with an unsent draft, marked next to their name. */
    var draftPages by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Where typed messages go. On a channel's page that channel; on a combined chat the channel the
     * user picked, or the one of the message being answered.
     */
    var sendChannel by mutableStateOf<String?>(null)
        private set

    /** The page on screen: a channel or a combined chat key. */
    var shownPage: String? = null
        private set

    /** The channels of [shownPage]: one, or all channels of the combined chat. */
    var shownChannels: List<String> = emptyList()
        private set

    /** The channel last written in per combined chat. */
    private val sendChannels = HashMap<String, String>()

    /**
     * Drafts of pages not on screen. A single shared input would carry text to the next page, and a
     * swipe mid-sentence would send it there.
     */
    private val drafts = HashMap<String, Draft>()

    /** The account [drafts] and the input belong to; null for a guest, who cannot write. */
    private var account: String? = null

    /** The drafts of the other accounts on the phone, by user id, until one of them is active again. */
    private val otherAccounts = HashMap<String, Map<String, Draft>>()

    /** A message this window sent: page, answered message and time, in case Twitch refuses it. */
    private class Sent(val text: String, val page: String, val replyTo: ChatItem?, val at: Long)

    /** The last message this window sent per channel. */
    private val sent = HashMap<String, Sent>()

    /** Pages with a message on its way. A second tap there would send the same text again. */
    private val sendingPages = HashSet<String>()

    /**
     * When slow mode lets the user write again, per channel. Only this window's messages are known.
     */
    private val slowUntil = mutableStateMapOf<String, Long>()

    /** When the user may write in [sendChannel] again; 0 if now. */
    val sendWaitUntil: Long get() = sendChannel?.let { slowUntil[it] } ?: 0L

    /**
     * Shows [page], which reads [channels]. False if nothing changed. The same page with other
     * channels is a combined chat that was edited; its input stays.
     */
    fun show(page: String?, channels: List<String>): Boolean {
        if (shownPage == page && shownChannels == channels) return false
        if (shownPage != page) swapDraft(shownPage, page)
        shownPage = page
        shownChannels = channels
        // A combined chat that lost the answered message's channel cannot answer it.
        if (replyTo?.channel !in channels) replyTo = null
        sendChannel = replyTo?.channel
            ?: page?.let { sendChannels[it] }?.takeIf { it in channels }
            ?: channels.firstOrNull()
        return true
    }

    /** Stores the input under [from] and restores the draft of [to]. */
    private fun swapDraft(from: String?, to: String?) {
        if (from != null) {
            val left = Draft(input, replyTo)
            if (left.isEmpty) drafts.remove(from) else drafts[from] = left
        }
        val draft = to?.let(drafts::remove)
        input = draft?.input ?: TextFieldValue("")
        replyTo = draft?.replyTo
        draftPages = drafts.keys.toSet()
    }

    /** Writes in [channel] from now on; it must be one of the page's channels. */
    fun selectSendChannel(channel: String) {
        if (channel !in shownChannels || channel == sendChannel) return
        sendChannel = channel
        shownPage?.let { sendChannels[it] = channel }
        // A reply goes to the channel of the message it answers.
        if (replyTo?.channel != channel) replyTo = null
    }

    /** Answers [item] with the next message. */
    fun startReply(item: ChatItem) {
        selectSendChannel(item.channel)
        replyTo = item
        replyStarts++
    }

    fun cancelReply() {
        replyTo = null
    }

    fun send() {
        val channel = sendChannel ?: return
        val page = shownPage ?: return
        if (!sendingPages.add(page)) return
        val submitted = Draft(input, replyTo)
        val text = submitted.input.text.trim()
        scope.launch {
            try {
                when (val result = outbox.send(channel, text, submitted.replyTo)) {
                    SendResult.Ok -> {
                        // A command is not a message: no slow mode, nothing to restore.
                        if (CommandParser.parse(text) == null) {
                            val at = now()
                            sent[channel] = Sent(text, page, submitted.replyTo, at)
                            val slow = outbox.slowSeconds(channel)
                            if (slow > 0) slowUntil[channel] = at + slow * 1000L
                        }
                        clearSent(page, submitted)
                    }
                    else -> outbox.report(result)
                }
            } finally {
                sendingPages.remove(page)
            }
        }
    }

    /** Takes [submitted] out of [page]'s input, which the user may have changed or left meanwhile. */
    private fun clearSent(page: String, submitted: Draft) {
        if (page == shownPage) {
            val remaining = Draft(input, replyTo).afterSending(submitted)
            input = remaining.input
            replyTo = remaining.replyTo
            onInputReplaced()
        } else {
            val remaining = drafts[page]?.afterSending(submitted) ?: return
            if (remaining.isEmpty) drafts.remove(page) else drafts[page] = remaining
            draftPages = drafts.keys.toSet()
        }
    }

    /**
     * Twitch refused the last message sent in [channel]. It goes back into the input of its page so
     * nothing has to be retyped, unless something new is being written there.
     */
    fun onRefused(channel: String) {
        val refused = sent.remove(channel) ?: return
        if (now() - refused.at > REFUSAL_WINDOW_MS) return
        // A refused message started no slow mode.
        slowUntil.remove(channel)
        val restored = TextFieldValue(refused.text, TextRange(refused.text.length))
        if (refused.page == shownPage) {
            if (input.text.isNotBlank()) return
            selectSendChannel(channel)
            input = restored
            if (replyTo == null) replyTo = refused.replyTo
            onInputReplaced()
        } else if (refused.page !in drafts) {
            drafts[refused.page] = Draft(restored, refused.replyTo)
            draftPages = drafts.keys.toSet()
        }
    }

    /**
     * Writes as [active] from now on, with its drafts, and forgets the drafts of accounts that are
     * no longer in [loggedIn]. Null is a guest or nobody.
     */
    fun useAccount(active: String?, loggedIn: Set<String>) {
        if (active != account) {
            val left = allDrafts()
            account?.let { if (left.isEmpty()) otherAccounts.remove(it) else otherAccounts[it] = left }
            drafts.clear()
            active?.let(otherAccounts::remove)?.let(drafts::putAll)
            account = active
            val draft = shownPage?.let(drafts::remove)
            input = draft?.input ?: TextFieldValue("")
            replyTo = draft?.replyTo?.takeIf { it.channel in shownChannels }
            replyTo?.let { sendChannel = it.channel }
            draftPages = drafts.keys.toSet()
        }
        otherAccounts.keys.retainAll(loggedIn)
    }

    /** The drafts of [account], the input on screen included. */
    private fun allDrafts(): Map<String, Draft> {
        val all = HashMap(drafts)
        val current = Draft(input, replyTo)
        shownPage?.let { if (!current.isEmpty) all[it] = current }
        return all
    }

    /** The drafts of every account and the chosen channels, as text; null if there is nothing. */
    fun save(): String? {
        val byAccount = HashMap<String, Map<String, SavedDraft>>()
        otherAccounts.forEach { (id, pages) -> byAccount[id] = pages.mapValues { SavedDraft.of(it.value) } }
        account?.let { id -> allDrafts().takeIf { it.isNotEmpty() }?.let { byAccount[id] = it.mapValues { SavedDraft.of(it.value) } } }
        if (byAccount.isEmpty() && sendChannels.isEmpty()) return null
        return AppJson.encodeToString(SavedDrafts(byAccount, sendChannels))
    }

    /**
     * Takes back what [save] wrote, before the first [useAccount]: the drafts wait until it names
     * their account, so none shows up for another one.
     */
    fun restore(saved: String) {
        check(account == null) { "Drafts are restored before an account is known" }
        val restored = runCatching { AppJson.decodeFromString<SavedDrafts>(saved) }.getOrNull() ?: return
        sendChannels.putAll(restored.sendChannels)
        restored.byAccount.forEach { (id, pages) -> otherAccounts[id] = pages.mapValues { it.value.toDraft() } }
    }

    private companion object {
        /**
         * How long after sending a refusal may still refer to that message. Twitch answers within a
         * second; the chat uses the same window to confirm sent messages.
         */
        const val REFUSAL_WINDOW_MS = 10_000L
    }
}

/** The drafts [Composer.save] keeps: per account and page, and the chosen channel per combined chat. */
@Serializable
internal class SavedDrafts(
    val byAccount: Map<String, Map<String, SavedDraft>> = emptyMap(),
    val sendChannels: Map<String, String> = emptyMap(),
)

/** A [Draft] as text. Of an answered message only what the reply strip and Twitch need. */
@Serializable
internal class SavedDraft(
    val text: String,
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val reply: SavedReply? = null,
) {
    fun toDraft() = Draft(
        TextFieldValue(text, TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))),
        reply?.toItem(),
    )

    companion object {
        fun of(draft: Draft) = SavedDraft(
            text = draft.input.text,
            selectionStart = draft.input.selection.start,
            selectionEnd = draft.input.selection.end,
            reply = draft.replyTo?.let(SavedReply::of),
        )
    }
}

@Serializable
internal class SavedReply(
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
