package dev.chatter.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chatter.app.AppContainer
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.channels.RemovedPage
import dev.chatter.app.channels.displayName
import dev.chatter.app.chat.ChatCommand
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.ChatRole
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.CommandParser
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.chat.InboxMention
import dev.chatter.app.chat.InboxWhisper
import dev.chatter.app.chat.SendLimits
import dev.chatter.app.chat.SendResult
import dev.chatter.app.crash.Crash
import dev.chatter.app.crash.DeviceInfo
import dev.chatter.app.emotes.Emoji
import dev.chatter.app.emotes.EmojiCatalog
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.net.HelixBlockedUser
import dev.chatter.app.net.HelixChannelSearch
import dev.chatter.app.net.HelixUser
import dev.chatter.app.settings.MobileData
import dev.chatter.app.settings.SettingsBackup
import dev.chatter.app.settings.TapAction
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.stats.Stats
import dev.chatter.app.ui.chat.ReadMark
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.util.Autocomplete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A snackbar message: a string resource and its argument. Resolved on screen, so a message that is
 * up follows a language change.
 */
data class UiMessage(val text: Int, val fill: String? = null)

/** A just removed channel or combined chat, with the name it was shown under. */
class Removal(val name: String, val removed: RemovedPage)

sealed interface Suggestion {
    data class EmoteSuggestion(val emote: Emote) : Suggestion
    data class UserSuggestion(val name: String) : Suggestion
    data class CommandSuggestion(val name: String, val usage: String) : Suggestion
    /** [shortcode] is the one that matched what was typed. */
    data class EmojiSuggestion(val emoji: Emoji, val shortcode: String) : Suggestion
}

class MainViewModel(private val c: AppContainer) : ViewModel() {
    val authState = c.auth.state

    /**
     * Whether the last login ended without the user logging out, so the login screen can say why.
     */
    val sessionExpired = c.auth.sessionExpired

    /** The user's Twitch id, for the supporter claim; GitHub Sponsors does not know it. */
    val ownTwitchId: String? get() = c.auth.account?.userId
    val ownLogin: String get() = c.auth.account?.login.orEmpty()

    /** All logged-in accounts, for the account switcher. */
    val accounts = c.auth.accounts
    val channels = c.channels.channels
    /** Channels and combined chats in pager order. */
    val pages = c.channels.pages
    /** Combined chats by their key in [pages]. */
    val groups = c.channels.groups
    val channelInfo = c.channels.info
    val customNames = c.channels.customNames
    val mutedChannels = c.channels.mutedChannels
    val hiddenUnread = c.channels.hiddenUnread
    val lastChannel = c.channels.lastChannel
    val unreadMentions = c.chat.unreadMentions
    val unreadMessages = c.chat.unreadMessages
    val settings = c.settings.settings
    val connection = c.irc.state
    val activePage = c.chat.activePage
    val modChannels = c.chat.rooms.moderated
    val powerSaveMode = c.powerSaveMode
    val saveData = c.dataSaving.active
    val backgroundStop = c.backgroundHealth.lastStop
    val batteryRestrictions = c.backgroundHealth.restrictions
    val roomStates = c.chat.rooms.states
    val roles = c.chat.rooms.roles
    val subscribedChannels = c.chat.rooms.subscribed
    /** Shared Chat partners of every channel currently sharing its chat. */
    val sharedChats = c.chat.sharedChats.sessions
    /** Name and picture of every Shared Chat partner seen so far, by channel id. */
    val chatPartners = c.chat.sharedChats.partners
    val emoteVersion = c.emotes.version
    val blockedUsers = c.blocked.blocked
    val blockedLogins = c.blocked.logins
    val nicknames = c.nicknames.nicknames
    val rules = c.rules.rules
    val inboxMentions = c.inbox.mentions
    val inboxWhispers = c.whisperInbox.whispers
    val mentionUnread = c.inbox.unreadCount
    val whisperUnread = c.whisperInbox.unreadCount

    /** The inbox button's badge: both tabs together. */
    val inboxUnread: StateFlow<Int> = combine(mentionUnread, whisperUnread) { m, w -> m + w }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    /**
     * Computed only while the stats page is open; otherwise counting stays two additions per
     * message.
     */
    val stats: StateFlow<Stats> = c.stats.live()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.stats.stats.value)
    val releases = c.changelog.releases
    /** Every mention as it arrives, for feedback while the chat screen is open. */
    val mentions = c.chat.allMentions
    /** Releases not read yet, shown once after an update. */
    val unreadReleases = c.changelog.unread
    /** Null until read, so the intro does not flash up for somebody who has seen it. */
    val notificationIntroSeen: StateFlow<Boolean?> = c.settings.notificationIntroSeen
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val imageLoader get() = c.imageLoader
    val staticImageLoader get() = c.staticImageLoader

    var input by mutableStateOf(TextFieldValue(""))
        private set
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
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set

    /**
     * True for a bubble's view model. A bubble lives in its notification, so it never clears that
     * notification, or it would close itself.
     */
    var inBubble = false

    /**
     * The page the pager should scroll to: one requested from outside (a notification tap), or one
     * just added or combined.
     */
    val requestedChannel = MutableStateFlow<String?>(null)

    /**
     * The inbox tab requested from outside (shortcut, whisper notification), or null. Cleared by
     * the inbox after switching.
     */
    val requestedInbox = MutableStateFlow<Int?>(null)

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    /** One-off feedback for the snackbar. */
    val messages = _messages.receiveAsFlow()

    // Only the latest; an undo for an earlier removal is not worth showing any more.
    private val _removals = Channel<Removal>(Channel.CONFLATED)
    /** Just removed channels and combined chats, each offered for undo once. */
    val removals = _removals.receiveAsFlow()

    private var suggestionJob: Job? = null

    init {
        // An outside service did not answer; reported once.
        viewModelScope.launch {
            c.trouble.unreachable.collect { _messages.send(UiMessage(R.string.error_service_down, it)) }
        }
        viewModelScope.launch { c.chat.refused.collect(::onRefused) }
    }

    /** A message this window sent: page and time, in case Twitch refuses it. */
    private class Sent(val text: String, val page: String?, val at: Long)

    /** The last message this window sent per channel. */
    private val sent = HashMap<String, Sent>()

    /**
     * When slow mode lets the user write again, per channel. Only this window's messages are known.
     */
    private val slowUntil = mutableStateMapOf<String, Long>()

    /** When the user may write in [sendChannel] again; 0 if now. */
    val sendWaitUntil: Long get() = sendChannel?.let { slowUntil[it] } ?: 0L

    /**
     * Twitch refused the last message sent in [channel]. It goes back into the input of its page so
     * nothing has to be retyped, unless something new is being written there.
     */
    private fun onRefused(channel: String) {
        val refused = sent.remove(channel) ?: return
        if (System.currentTimeMillis() - refused.at > REFUSAL_WINDOW_MS) return
        // A refused message started no slow mode.
        slowUntil.remove(channel)
        val restored = TextFieldValue(refused.text, TextRange(refused.text.length))
        if (refused.page == shownPage) {
            if (input.text.isNotBlank()) return
            selectSendChannel(channel)
            input = restored
            updateSuggestions()
        } else if (refused.page != null && refused.page !in drafts) {
            drafts[refused.page] = Draft(restored, null)
            draftPages = drafts.keys.toSet()
        }
    }

    /**
     * The page this window shows: a channel or a combined chat key. Differs from [activePage] while
     * a bubble is open; that one belongs to the chat screen.
     */
    private var shownPage: String? = null

    /** The channels of [shownPage]: one, or all channels of the combined chat. */
    private var shownChannels: List<String> = emptyList()

    /**
     * Where typed messages go. On a channel's page that channel; on a combined chat the channel the
     * user picked, or the one of the message being answered.
     */
    var sendChannel by mutableStateOf<String?>(null)
        private set

    /** The channel last written in per combined chat. */
    private val sendChannels = HashMap<String, String>()

    /** Unsent input on a page the user left: the text and the message it answers. */
    private class Draft(val input: TextFieldValue, val replyTo: ChatItem?)

    /**
     * Drafts of pages not on screen. A single shared input would carry text to the next page, and a
     * swipe mid-sentence would send it there.
     */
    private val drafts = HashMap<String, Draft>()

    /** Stores the input under [from] and restores the draft of [to]. */
    private fun swapDraft(from: String?, to: String?) {
        if (from != null) {
            if (input.text.isNotBlank() || replyTo != null) drafts[from] = Draft(input, replyTo)
            else drafts.remove(from)
        }
        val draft = to?.let(drafts::remove)
        input = draft?.input ?: TextFieldValue("")
        replyTo = draft?.replyTo
        draftPages = drafts.keys.toSet()
    }

    /** The newest message each page had on screen; see [onSeen]. */
    private val seen = HashMap<String, ReadMark>()

    /**
     * How far each page had been read when the user last left it. Fixed until they leave again, so
     * the line stays put while they catch up.
     */
    private val readMarks = mutableStateMapOf<String, ReadMark>()

    /** [mark] is now the newest message on screen for [page]. */
    fun onSeen(page: String, mark: ReadMark) {
        seen[page] = mark
    }

    /** Where the unread line of [page] goes; null for a page never left. */
    fun readMark(page: String): ReadMark? = readMarks[page]

    /** The user is leaving [page], for another page or app. */
    private fun leave(page: String) {
        seen[page]?.let { readMarks[page] = it }
    }

    /** The messages of a channel, or of a combined chat by its key. */
    fun chat(page: String) = c.chat.messages(page)

    /** The channels a page reads from. */
    private fun channelsOf(page: String): List<String> =
        if (ChannelGroup.isKey(page)) groups.value[page]?.channels.orEmpty() else listOf(page)

    /**
     * Moves this window to [page]. Selecting the current page again reloads a combined chat whose
     * channels changed.
     */
    fun selectChannel(page: String?) {
        val channels = page?.let(::channelsOf).orEmpty()
        if (shownPage == page && shownChannels == channels) return
        val samePage = shownPage == page
        if (!samePage) {
            shownPage?.let(::leave)
            swapDraft(shownPage, page)
        }
        shownPage = page
        shownChannels = channels
        c.chat.windows.setChannels(this, channels.toSet())
        // The chat screen follows this; a bubble must not move it.
        if (!inBubble) c.chat.activePage.value = page
        channels.forEach {
            if (!inBubble) c.notifier.clear(it)
            c.chat.clearUnread(it)
            // Reading a channel reads its mentions.
            viewModelScope.launch { c.inbox.markChannelRead(it) }
        }
        // Remembered for after a restart: the chat screen's page, not a bubble's channel.
        if (page != null && !inBubble) rememberLastChannel(page)
        sendChannel = page?.let { sendChannels[it] }?.takeIf { it in channels } ?: channels.firstOrNull()
        // A combined chat that lost the answered message's channel cannot answer it.
        if (replyTo?.channel !in channels) replyTo = null
        suggestions = emptyList()
    }

    /** Writes in [channel] from now on; it must be one of the page's channels. */
    fun selectSendChannel(channel: String) {
        if (channel !in shownChannels || channel == sendChannel) return
        sendChannel = channel
        shownPage?.let { sendChannels[it] = channel }
        // A reply goes to the channel of the message it answers.
        if (replyTo?.channel != channel) replyTo = null
        updateSuggestions()
    }

    /** Tells the chat that the whisper tab is in front, so arriving whispers do not notify. */
    fun setWhispersVisible(visible: Boolean) {
        c.chat.windows.whispersVisible.value = visible
        if (visible) c.notifier.clearWhispers()
    }

    /**
     * The last channel is written after swiping settles. Each write rewrites the file and reparses
     * every flow on that store, too much for channels the user only passes through.
     */
    private var pendingLastChannel: String? = null
    private var lastChannelJob: Job? = null

    private fun rememberLastChannel(channel: String) {
        pendingLastChannel = channel
        lastChannelJob?.cancel()
        lastChannelJob = viewModelScope.launch {
            delay(LAST_CHANNEL_DELAY_MS)
            writeLastChannel()
        }
    }

    private fun writeLastChannel() {
        val channel = pendingLastChannel ?: return
        pendingLastChannel = null
        lastChannelJob?.cancel()
        viewModelScope.launch { c.channels.setLastChannel(channel) }
    }

    fun setUiVisible(visible: Boolean) {
        c.chat.windows.setVisible(this, visible, shownChannels.toSet())
        // Leaving may come before the delay ends; then this is the last chance to write it.
        if (!visible) {
            writeLastChannel()
            shownPage?.let(::leave)
        }
        if (visible) {
            c.connect()
            shownChannels.forEach {
                c.chat.clearUnread(it)
                if (!inBubble) c.notifier.clear(it)
            }
        }
    }

    /** The window is gone; it reads nothing any more. */
    override fun onCleared() {
        c.chat.windows.setVisible(this, visible = false, channels = emptySet())
        super.onCleared()
    }

    // ---- Input & autocomplete --------------------------------------------------------------

    fun onInputChange(value: TextFieldValue) {
        input = value
        updateSuggestions()
    }

    private fun updateSuggestions() {
        suggestionJob?.cancel()
        val channel = sendChannel
        val word = Autocomplete.currentWord(input.text, input.selection.start)
        if (channel == null || word == null) {
            suggestions = emptyList()
            return
        }
        // Off the main thread: ranking runs per keystroke over every emote the account has, a few
        // thousand for a well subscribed one.
        suggestionJob = viewModelScope.launch {
            suggestions = withContext(Dispatchers.Default) { rank(channel, word) }
        }
    }

    private suspend fun rank(channel: String, word: Autocomplete.Word): List<Suggestion> {
        val shortcode = EmojiCatalog.typedShortcode(word.text)
        return if (word.start == 0 && word.text.startsWith("/")) {
            val typed = word.text.substring(1).lowercase()
            CommandParser.COMMANDS.filterKeys { it.startsWith(typed) }
                .map { (name, usage) -> Suggestion.CommandSuggestion(name, usage) }
        } else if (shortcode != null) {
            if (!settings.value.emoteSuggestions) emptyList()
            else EmojiCatalog.search(shortcode, c.emoji.all()).map { (emoji, code) -> Suggestion.EmojiSuggestion(emoji, code) }
        } else if (word.text.startsWith("@")) {
            if (!settings.value.userSuggestions) emptyList()
            else Autocomplete.rankUsers(word.text, c.chat.chatters(channel)).map { Suggestion.UserSuggestion(it) }
        } else if (word.text.length >= 2) {
            val s = settings.value
            // A plain word can be either: emotes first, since they are typed without "@", names
            // after.
            val emotes = if (!s.emoteSuggestions) emptyList() else {
                Autocomplete.rankEmotes(word.text, emotesFor(channel)).map { Suggestion.EmoteSuggestion(it) }
            }
            val users = if (!s.userSuggestions) emptyList() else {
                Autocomplete.rankUsers(word.text, c.chat.chatters(channel), limit = 15)
                    .map { Suggestion.UserSuggestion(it) }
            }
            emotes + users
        } else emptyList()
    }

    fun applySuggestion(s: Suggestion) {
        val word = Autocomplete.currentWord(input.text, input.selection.start) ?: return
        val value = when (s) {
            is Suggestion.EmoteSuggestion -> s.emote.name.also { rememberEmote(it) }
            // An "@" the user typed stays either way.
            is Suggestion.UserSuggestion ->
                if (settings.value.mentionWithAt || word.text.startsWith("@")) "@${s.name}" else s.name
            is Suggestion.CommandSuggestion -> "/${s.name}"
            is Suggestion.EmojiSuggestion -> s.emoji.value.also { rememberEmote(it) }
        }
        val (text, cursor) = Autocomplete.replace(input.text, word, value)
        input = TextFieldValue(text, TextRange(cursor))
        suggestions = emptyList()
    }

    fun insertEmote(emote: Emote) {
        val (text, cursor) = Autocomplete.insert(input.text, input.selection.start, emote.name)
        input = TextFieldValue(text, TextRange(cursor))
        rememberEmote(emote.name)
    }

    fun insertEmoji(emoji: Emoji) {
        val (text, cursor) = Autocomplete.insert(input.text, input.selection.start, emoji.value)
        input = TextFieldValue(text, TextRange(cursor))
        rememberEmote(emoji.value)
    }

    /** Every emoji the phone can draw, for the picker. */
    suspend fun emoji(): List<Emoji> = c.emoji.all()

    /** Mentions the author of [item], in the channel it was written in on a combined chat. */
    fun mention(item: ChatItem) {
        val name = item.displayName ?: item.login ?: return
        selectSendChannel(item.channel)
        val (text, cursor) = Autocomplete.insert(input.text, input.selection.start, "@$name")
        input = TextFieldValue(text, TextRange(cursor))
    }

    private fun rememberEmote(name: String) {
        viewModelScope.launch { c.settings.addRecentEmote(name) }
    }

    /** What [emotesFor] last returned and its inputs. */
    private class EmoteList(
        val channel: String?,
        val version: Int,
        val providers: Set<EmoteProvider>,
        val unlisted: Boolean,
        val emotes: List<Emote>,
    )

    @Volatile private var lastEmoteList: EmoteList? = null

    /**
     * Emotes the user can type in [channel]; unlisted 7TV emotes only if enabled. Cached until an
     * input changes, since building it walks every emote and autocomplete asks per keystroke.
     */
    fun emotesFor(channel: String?): List<Emote> {
        val s = settings.value
        val version = emoteVersion.value
        lastEmoteList?.let {
            if (it.channel == channel && it.version == version &&
                it.providers == s.emoteProviders && it.unlisted == s.showUnlisted7tv
            ) return it.emotes
        }
        val emotes = c.emotes.available(channel?.let { c.chat.rooms.id(it) })
            .filter { it.provider in s.emoteProviders }
            .filterNot { !s.showUnlisted7tv && it.unlisted }
        lastEmoteList = EmoteList(channel, version, s.emoteProviders, s.showUnlisted7tv, emotes)
        return emotes
    }

    /** What the author of [item] said in its channel lately, oldest first. From memory. */
    suspend fun recentMessagesOf(item: ChatItem): List<ChatItem> {
        val login = item.login ?: return emptyList()
        return c.chat.messagesFrom(item.channel, login)
    }

    /** The Twitch profile of [item]'s author; null if Twitch is unreachable. */
    suspend fun profileOf(item: ChatItem): HelixUser? {
        val login = item.login ?: return null
        return runCatching { c.helix.users(listOf(login)).firstOrNull() }.getOrNull()
    }

    /** Twitch badge image for the user's role in a channel (moderator sword etc.). */
    fun roleBadge(channel: String, role: ChatRole): Badge? =
        role.badgeTag?.let { c.badges.resolve(c.chat.rooms.id(channel), it, userId = null).firstOrNull() }

    /**
     * Blocks or unblocks on Twitch. Needs the Twitch id from the user card's profile, so it is
     * offered once that loaded.
     */
    fun setBlocked(user: HelixUser, blocked: Boolean) {
        viewModelScope.launch {
            val target = HelixBlockedUser(user.id, user.login, user.displayName)
            if (!c.blocked.setBlocked(target, blocked)) _messages.send(UiMessage(R.string.error_block_failed))
        }
    }

    fun unblock(user: HelixBlockedUser) {
        viewModelScope.launch {
            if (!c.blocked.setBlocked(user, blocked = false)) _messages.send(UiMessage(R.string.error_block_failed))
        }
    }

    /** Blocks someone by typed name; Twitch only takes ids, so the login is looked up first. */
    fun blockByLogin(login: String) {
        viewModelScope.launch {
            val clean = login.trim().removePrefix("@").lowercase()
            val user = runCatching { c.helix.users(listOf(clean)).firstOrNull() }.getOrNull()
            if (user == null) {
                _messages.send(UiMessage(R.string.error_user_unknown))
                return@launch
            }
            val target = HelixBlockedUser(user.id, user.login, user.displayName)
            if (!c.blocked.setBlocked(target, blocked = true)) _messages.send(UiMessage(R.string.error_block_failed))
        }
    }

    // ---- Backup ------------------------------------------------------------------------------

    /** The whole configuration as a backup file. */
    fun exportBackup(): String = c.backup.export()

    /** The backup in [text], checked but not applied; null if it is not a Chatter backup. */
    fun readBackup(text: String): SettingsBackup? = c.backup.read(text)

    /** Restores a backup from [readBackup] after the user has seen what it changes. */
    suspend fun applyBackup(backup: SettingsBackup) = c.backup.apply(backup)

    // ---- Highlight rules ---------------------------------------------------------------------

    fun saveRule(rule: ChatRule) {
        viewModelScope.launch { c.rules.save(rule) }
    }

    fun deleteRule(rule: ChatRule) {
        viewModelScope.launch { c.rules.delete(rule.id) }
    }

    fun setRuleEnabled(rule: ChatRule, enabled: Boolean) {
        viewModelScope.launch { c.rules.setEnabled(rule.id, enabled) }
    }

    // ---- Mention inbox -----------------------------------------------------------------------

    fun markInboxRead(mention: InboxMention) {
        if (mention.read) return
        viewModelScope.launch { c.inbox.markRead(mention.id) }
    }

    /**
     * Mentions currently on screen. Opening a channel reads its mentions, but returning to the app
     * on the same channel is not an opening, and mentions that arrived meanwhile would stay unread.
     */
    fun onMentionsSeen(ids: Set<String>) {
        // Called on every scroll that changes visible mentions, almost always read ones; only
        // unread ones are worth a write.
        if (c.inbox.mentions.value.none { !it.read && it.id in ids }) return
        viewModelScope.launch { c.inbox.markRead(ids) }
    }

    fun markInboxRead() {
        viewModelScope.launch { c.inbox.markAllRead() }
    }

    fun clearInbox() {
        viewModelScope.launch { c.inbox.clear() }
    }

    /** Whispers a chatter from their card. Returns the line to show, sent or not. */
    suspend fun whisperTo(login: String, userId: String?, text: String): String =
        c.whisperSender.send(login, userId, text).message

    /** Answers a whisper. Returns the line to show, sent or not. */
    suspend fun sendWhisper(whisper: InboxWhisper, text: String): String =
        c.whisperSender.send(whisper.login, whisper.userId, text).message

    fun markWhisperRead(whisper: InboxWhisper) {
        if (whisper.read) return
        viewModelScope.launch { c.whisperInbox.markRead(whisper.id) }
    }

    fun markWhispersRead() {
        viewModelScope.launch { c.whisperInbox.markAllRead() }
    }

    fun clearWhispers() {
        viewModelScope.launch { c.whisperInbox.clear() }
    }

    /** Sets a nickname for a chatter in every channel. Blank clears it. */
    fun setNickname(login: String, nickname: String) {
        viewModelScope.launch { c.nicknames.set(login, nickname) }
    }

    fun deleteMessage(item: ChatItem) {
        c.chat.runCommand(item.channel, ChatCommand.Delete(item.id))
    }

    fun timeoutUser(item: ChatItem, seconds: Int = 600) {
        val login = item.login ?: return
        c.chat.runCommand(item.channel, ChatCommand.Timeout(login, seconds, null))
    }

    fun banUser(item: ChatItem) {
        val login = item.login ?: return
        c.chat.runCommand(item.channel, ChatCommand.Ban(login, null))
    }

    /**
     * Answers [item] with the next message. False if it cannot be answered: a notice, the user's
     * own message before Twitch confirmed it, or anything for a guest.
     */
    fun startReply(item: ChatItem): Boolean {
        if (c.auth.account == null || !item.canReply || item.id.startsWith("local-")) return false
        selectSendChannel(item.channel)
        replyTo = item
        replyStarts++
        return true
    }

    fun cancelReply() {
        replyTo = null
    }

    fun send() {
        val channel = sendChannel ?: return
        val text = input.text
        val reply = replyTo
        viewModelScope.launch {
            when (c.chat.send(channel, text, reply)) {
                SendResult.Ok -> {
                    // A command is not a message: no slow mode, nothing to restore.
                    if (CommandParser.parse(text.trim()) == null) {
                        val now = System.currentTimeMillis()
                        sent[channel] = Sent(text.trim(), shownPage, now)
                        val slow = SendLimits.slowSeconds(roomStates.value[channel], roles.value[channel])
                        if (slow > 0) slowUntil[channel] = now + slow * 1000L
                    }
                    input = TextFieldValue("")
                    replyTo = null
                    suggestions = emptyList()
                }
                SendResult.Empty -> Unit
                SendResult.NotConnected -> _messages.send(UiMessage(R.string.error_not_connected))
                SendResult.RateLimited -> _messages.send(UiMessage(R.string.error_rate_limited))
                SendResult.CommandError -> Unit // the hint is shown in the chat
            }
        }
    }

    // ---- Bug reports -------------------------------------------------------------------------

    /** The last recorded crash; null if there is none. */
    suspend fun lastCrash(): Crash? = withContext(Dispatchers.IO) { c.crashLog.latest() }

    /** App and device details for bug reports. */
    val device: DeviceInfo = DeviceInfo.current(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    // ---- Channels ----------------------------------------------------------------------------

    fun addChannel(name: String) {
        viewModelScope.launch {
            val login = c.channels.add(name)
            if (login == null) _messages.send(UiMessage(R.string.error_invalid_channel))
            else {
                requestedChannel.value = login
                c.channels.refreshLive()
            }
        }
    }

    /**
     * Removes a channel or combined chat (by key) and offers undo; its name, settings and places in
     * combined chats are a lot to lose to a slip.
     */
    fun removeChannel(page: String) {
        val info = c.channels.info.value
        val name = c.channels.groups.value[page]?.displayName(info) ?: info[page]?.displayName ?: page
        viewModelScope.launch {
            val removed = c.channels.remove(page) ?: return@launch
            _removals.send(Removal(name, removed))
        }
    }

    /** Puts back what [removal] took and scrolls to it. */
    fun undoRemoval(removal: Removal) {
        viewModelScope.launch {
            c.channels.putBack(removal.removed)
            requestedChannel.value = removal.removed.page
        }
    }

    /**
     * Creates a combined chat of [channels], or changes the one [key] names. A new one is opened
     * right away, like a newly added channel.
     */
    fun saveGroup(key: String?, name: String, channels: Collection<String>) {
        viewModelScope.launch {
            val saved = c.channels.saveGroup(key, name, channels)
            if (key == null) requestedChannel.value = saved
        }
    }

    fun setUnreadInTitleBar(v: Boolean) {
        viewModelScope.launch { c.settings.setUnreadInTitleBar(v) }
    }

    fun setChannelTabs(v: Boolean) {
        viewModelScope.launch { c.settings.setChannelTabs(v) }
    }

    fun setHaptics(v: Boolean) {
        viewModelScope.launch { c.settings.setHaptics(v) }
    }

    fun setKeepScreenOn(v: Boolean) {
        viewModelScope.launch { c.settings.setKeepScreenOn(v) }
    }

    fun setBubbles(v: Boolean) {
        viewModelScope.launch { c.settings.setBubbles(v) }
    }

    fun setSenderAvatars(v: Boolean) {
        viewModelScope.launch { c.settings.setSenderAvatars(v) }
    }

    fun setBadgeProvider(provider: BadgeProvider, enabled: Boolean) {
        viewModelScope.launch {
            c.settings.updateBadgeProviders { if (enabled) it + provider else it - provider }
        }
    }

    fun setEmoteProvider(provider: EmoteProvider, enabled: Boolean) {
        viewModelScope.launch {
            c.settings.updateEmoteProviders { if (enabled) it + provider else it - provider }
        }
    }

    fun setTimestamps(v: TimestampFormat) {
        viewModelScope.launch { c.settings.setTimestamps(v) }
    }

    fun setMessageTap(v: TapAction) {
        viewModelScope.launch { c.settings.setMessageTap(v) }
    }

    fun setNameTap(v: TapAction) {
        viewModelScope.launch { c.settings.setNameTap(v) }
    }

    fun setCopyFirst(v: Boolean) {
        viewModelScope.launch { c.settings.setCopyFirst(v) }
    }

    fun setShowDeleted(v: Boolean) {
        viewModelScope.launch { c.settings.setShowDeleted(v) }
    }

    fun setEmoteSuggestions(v: Boolean) {
        viewModelScope.launch { c.settings.setEmoteSuggestions(v) }
    }

    fun setMentionWithAt(v: Boolean) {
        viewModelScope.launch { c.settings.setMentionWithAt(v) }
    }

    fun setUserSuggestions(v: Boolean) {
        viewModelScope.launch { c.settings.setUserSuggestions(v) }
    }

    fun setChannelUnreadVisible(login: String, visible: Boolean) {
        viewModelScope.launch { c.channels.setUnreadVisible(login, visible) }
    }

    fun setChannelNotify(login: String, enabled: Boolean) {
        viewModelScope.launch { c.channels.setNotify(login, enabled) }
    }

    fun renameChannel(login: String, name: String) {
        viewModelScope.launch { c.channels.rename(login, name) }
    }

    /** The Twitch name, to show what clearing a custom name restores. */
    fun twitchName(login: String): String = c.channels.twitchName(login)

    /** Moves a channel or combined chat (by key). */
    fun moveChannel(page: String, delta: Int) {
        viewModelScope.launch { c.channels.move(page, delta) }
    }

    suspend fun searchChannels(query: String): List<HelixChannelSearch> = c.channels.search(query)

    /** Polls live status while the UI is visible; cancelled when it goes away. */
    suspend fun pollLiveStatus() {
        while (true) {
            c.channels.refreshLive()
            delay(120_000)
        }
    }

    // ---- Login / settings ----------------------------------------------------------------------

    fun loginUrl(): String = c.auth.authorizeUrl()

    /** Twitch's login page for another account; it has to ask who logs in. */
    fun addAccountUrl(): String = c.auth.authorizeUrl(forceVerify = true)

    /**
     * Twitch's login page to log the active account in again, for a login that lacks scopes. The
     * old token is revoked once the new one is in.
     */
    fun reauthorizeUrl(): String = c.auth.authorizeUrl(forceVerify = true)

    suspend fun handleRedirect(url: String): Result<Unit>? = c.auth.handleRedirect(url)

    /** Reads chats without an account; see AuthState.Guest. */
    fun continueAsGuest() {
        viewModelScope.launch { c.auth.continueAsGuest() }
    }

    /** From guest mode back to the login screen. */
    fun leaveGuest() {
        viewModelScope.launch { c.auth.leaveGuest() }
    }

    /** Profiling builds' way past the login: reads [channel] as a guest. */
    fun readAsGuest(channel: String) {
        viewModelScope.launch {
            if (c.auth.account == null) c.auth.continueAsGuest()
            addChannel(channel)
        }
    }

    fun logout() {
        viewModelScope.launch {
            c.disconnect()
            c.auth.logout()
            // Another account may have taken over and needs the connection the logout closed.
            c.connect()
        }
    }

    /** Acts as another logged-in account from now on. */
    fun switchAccount(userId: String) {
        viewModelScope.launch { c.auth.switchTo(userId) }
    }

    /** Logs one account out, active or not. */
    fun removeAccount(userId: String) {
        viewModelScope.launch {
            if (userId == c.auth.account?.userId) c.disconnect()
            c.auth.remove(userId)
            c.connect()
        }
    }

    /** Refreshes the stored names and pictures of all accounts. */
    fun refreshAccounts() {
        viewModelScope.launch { c.auth.refreshProfiles() }
    }

    /** The active account's Twitch profile; null if Twitch is unreachable. */
    suspend fun ownProfile(): HelixUser? {
        val login = c.auth.account?.login ?: return null
        return runCatching { c.helix.users(listOf(login)).firstOrNull() }.getOrNull()
    }

    /** How many channels the account follows; null if Twitch did not say. */
    suspend fun followedChannels(): Int? {
        val userId = c.auth.account?.userId ?: return null
        return runCatching { c.helix.followedCount(userId) }.getOrNull()
    }

    fun setFontSize(v: Float) {
        viewModelScope.launch { c.settings.setFontSize(v) }
    }

    fun setMessageLimit(v: Int) {
        viewModelScope.launch { c.settings.setMessageLimit(v) }
    }

    /** Adds highlight words. Commas split, so a pasted list becomes separate words. */
    fun addMentionKeyword(input: String) {
        viewModelScope.launch { c.settings.updateMentionKeywords { withWords(it, input) ?: it } }
    }

    fun removeMentionKeyword(word: String) {
        viewModelScope.launch {
            c.settings.updateMentionKeywords { list -> list.filterNot { it.equals(word, ignoreCase = true) } }
        }
    }

    fun addMuteKeyword(input: String) {
        viewModelScope.launch { c.settings.updateMuteKeywords { withWords(it, input) ?: it } }
    }

    fun removeMuteKeyword(word: String) {
        viewModelScope.launch {
            c.settings.updateMuteKeywords { list -> list.filterNot { it.equals(word, ignoreCase = true) } }
        }
    }

    /** The list with [input] added, or null if it holds nothing new. */
    private fun withWords(current: List<String>, input: String): List<String>? {
        val added = input.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && current.none { word -> word.equals(it, ignoreCase = true) } }
            .distinctBy { it.lowercase() }
        return if (added.isEmpty()) null else current + added
    }

    fun setThemeMode(v: ThemeMode) {
        viewModelScope.launch { c.settings.setThemeMode(v) }
    }

    fun setDynamicColor(v: Boolean) {
        viewModelScope.launch { c.settings.setDynamicColor(v) }
    }

    /** Reads the battery settings again, e.g. after the user changed them. */
    fun refreshBatteryRestrictions() = c.backgroundHealth.refresh()

    fun dismissBackgroundStop() = c.backgroundHealth.dismiss()

    fun setMobileData(v: MobileData) {
        viewModelScope.launch { c.settings.setMobileData(v) }
    }

    fun setPureBlack(v: Boolean) {
        viewModelScope.launch { c.settings.setPureBlack(v) }
    }

    fun setHighlightFirstMessages(v: Boolean) {
        viewModelScope.launch { c.settings.setHighlightFirstMessages(v) }
    }

    fun setNameColors(v: NameColorPalette) {
        viewModelScope.launch { c.settings.setNameColors(v) }
    }

    fun setAlternateBackground(v: Boolean) {
        viewModelScope.launch { c.settings.setAlternateBackground(v) }
    }

    fun setHighlightColor(v: Int) {
        viewModelScope.launch { c.settings.setHighlightColor(v) }
    }

    fun setSmoothScrolling(v: Boolean) {
        viewModelScope.launch { c.settings.setSmoothScrolling(v) }
    }

    fun setInlineImages(v: Boolean) {
        viewModelScope.launch { c.settings.setInlineImages(v) }
    }

    fun setFullLinks(v: Boolean) {
        viewModelScope.launch { c.settings.setFullLinks(v) }
    }

    /** Turns whatever was pasted into bare hosts. */
    fun addImageHost(input: String) {
        val added = input.split(',').map { ImageLinks.cleanHost(it) }.filter { it.isNotEmpty() }.distinct()
        if (added.isEmpty()) return
        viewModelScope.launch { c.settings.updateImageHosts { current -> current + added.filter { it !in current } } }
    }

    fun removeImageHost(host: String) {
        viewModelScope.launch { c.settings.updateImageHosts { it - host } }
    }

    /** Edits [old] in place, keeping the user's order. */
    fun editImageHost(old: String, input: String) {
        val host = ImageLinks.cleanHost(input)
        if (host.isEmpty() || host == old) return
        viewModelScope.launch {
            c.settings.updateImageHosts { current ->
                val at = current.indexOf(old)
                when {
                    at < 0 -> current
                    // Already in the list further down; would be listed twice.
                    host in current -> current - old
                    else -> current.toMutableList().also { it[at] = host }
                }
            }
        }
    }

    /** Back to the default hosts. */
    fun resetImageHosts() {
        viewModelScope.launch { c.settings.updateImageHosts { ImageLinks.DEFAULT_HOSTS } }
    }

    fun setCarouselChannels(v: Boolean) {
        viewModelScope.launch { c.settings.setCarouselChannels(v) }
    }

    fun setEmotesEnabled(v: Boolean) {
        viewModelScope.launch { c.settings.setEmotesEnabled(v) }
    }

    fun setZeroWidthEmotes(v: Boolean) {
        viewModelScope.launch { c.settings.setZeroWidthEmotes(v) }
    }

    fun setShowUnlisted7tv(v: Boolean) {
        viewModelScope.launch { c.settings.setShowUnlisted7tv(v) }
    }

    fun setSevenTvEvents(v: Boolean) {
        viewModelScope.launch { c.settings.setSevenTvEvents(v) }
    }

    fun setLoadHistory(v: Boolean) {
        viewModelScope.launch { c.settings.setLoadHistory(v) }
    }

    fun setAnimatedEmotes(v: Boolean) {
        viewModelScope.launch { c.settings.setAnimatedEmotes(v) }
    }

    fun setSlowIdleEmotes(v: Boolean) {
        viewModelScope.launch { c.settings.setSlowIdleEmotes(v) }
    }

    fun resetStats() {
        viewModelScope.launch { c.stats.reset() }
    }

    /** The update notes were seen and should not come back. */
    fun markChangelogRead() = c.changelog.markRead()

    fun markNotificationIntroSeen() {
        viewModelScope.launch { c.settings.setNotificationIntroSeen() }
    }

    /** A newer GitHub release, for the GitHub APK; null otherwise. */
    val availableUpdate = c.updates.available

    fun checkForUpdate() = c.updates.checkIfDue()

    fun setUpdateCheck(v: Boolean) {
        viewModelScope.launch {
            c.settings.setUpdateCheck(v)
            // After a long time off, the stored answer may be stale.
            if (v) c.updates.checkIfDue()
        }
    }

    private companion object {
        /** How long a channel must stay on screen to be remembered as the last one. */
        const val LAST_CHANNEL_DELAY_MS = 1_500L

        /**
         * How long after sending a refusal may still refer to that message. Twitch answers within a
         * second; the chat uses the same window to confirm sent messages.
         */
        const val REFUSAL_WINDOW_MS = 10_000L
    }
}
