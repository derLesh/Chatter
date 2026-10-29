package dev.chatter.app.ui

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chatter.app.AppContainer
import dev.chatter.app.BuildConfig
import dev.chatter.app.R
import dev.chatter.app.ai.ModelFailure
import dev.chatter.app.ai.ModelTrouble
import dev.chatter.app.auth.AuthState
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
import dev.chatter.app.chat.PinnedMessage
import dev.chatter.app.chat.PostSite
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
import dev.chatter.app.net.HelixFollowedChannel
import dev.chatter.app.net.HelixUser
import dev.chatter.app.settings.MobileData
import dev.chatter.app.settings.PostPictureSize
import dev.chatter.app.settings.SettingsBackup
import dev.chatter.app.settings.SettingsRepository
import dev.chatter.app.settings.SpecialMessage
import dev.chatter.app.settings.TapAction
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.stats.Stats
import dev.chatter.app.ui.chat.ReadMark
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.util.AppIcon
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
import java.util.Locale

/**
 * A snackbar message: a string resource and its argument. Resolved on screen, so a message that is
 * up follows a language change.
 */
data class UiMessage(val text: Int, val fill: String? = null)

/** A message to scroll to: [id] in [channel]'s chat. */
data class MessageJump(val channel: String, val id: String)

/**
 * A catch-up summary of [count] messages in [page], growing while the model writes it. [trouble]
 * says why it stopped short; [leftOut] are the messages the safety check refused.
 */
data class CatchUpSummary(
    val page: String,
    val count: Int,
    val text: String = "",
    val leftOut: List<ChatItem> = emptyList(),
    val done: Boolean = false,
    val trouble: ModelTrouble? = null,
)

/** A just removed channel or combined chat, with the name it was shown under. */
class Removal(val name: String, val removed: RemovedPage)

sealed interface Suggestion {
    data class EmoteSuggestion(val emote: Emote) : Suggestion
    data class UserSuggestion(val name: String) : Suggestion
    data class CommandSuggestion(val name: String, val usage: String) : Suggestion
    /** [shortcode] is the one that matched what was typed. */
    data class EmojiSuggestion(val emoji: Emoji, val shortcode: String) : Suggestion
}

class MainViewModel(private val c: AppContainer, savedState: SavedStateHandle) : ViewModel() {
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
    val channelsWithNews = c.chat.channelsWithNews
    val settings = c.settings.settings
    val recentEmotes = c.settings.recentEmotes
    val connection = c.irc.state
    val activePage = c.chat.activePage
    val modChannels = c.chat.rooms.moderated
    val powerSaveMode = c.powerSaveMode
    /** Whether Gemini Nano can write catch-up summaries on this phone. */
    val onDeviceModel = c.onDeviceModel.state
    val saveData = c.dataSaving.active
    val backgroundStop = c.backgroundHealth.lastStop
    val batteryRestrictions = c.backgroundHealth.restrictions
    val roomStates = c.chat.rooms.states
    val roles = c.chat.rooms.roles
    val subscribedChannels = c.chat.rooms.subscribed
    /** The pinned message per channel, where the user moderates. */
    val pins = c.chat.pins.pins

    /** Pins the user put away; the next pin shows again. Message ids. */
    var hiddenPins by mutableStateOf(emptySet<String>())
        private set

    fun hidePin(pin: PinnedMessage) {
        hiddenPins = hiddenPins + pin.item.id
    }
    /** Shared Chat partners of every channel currently sharing its chat. */
    val sharedChats = c.chat.sharedChats.sessions
    /** Name and picture of every Shared Chat partner seen so far, by channel id. */
    val chatPartners = c.chat.sharedChats.partners
    val emoteVersion = c.emotes.version
    val blockedUsers = c.blocked.blocked
    val blockedLogins = c.blocked.logins
    val nicknames = c.nicknames.nicknames
    /** Reward titles by reward id, in the user's own channel. */
    val rewardNames = c.chat.rewards.names
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
    /** Null until known, like [notificationIntroSeen]. */
    val onboardingSeen: StateFlow<Boolean?> = c.settings.onboardingSeen
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val imageLoader get() = c.imageLoader
    val staticImageLoader get() = c.staticImageLoader
    val posts get() = c.posts

    private val composer = Composer(
        scope = viewModelScope,
        outbox = object : Outbox {
            override suspend fun send(channel: String, text: String, replyTo: ChatItem?) = c.chat.send(channel, text, replyTo)

            override fun slowSeconds(channel: String) =
                SendLimits.slowSeconds(roomStates.value[channel], roles.value[channel])

            override suspend fun report(result: SendResult) {
                when (result) {
                    SendResult.NotConnected -> _messages.send(UiMessage(R.string.error_not_connected))
                    SendResult.RateLimited -> _messages.send(UiMessage(R.string.error_rate_limited))
                    // A command error is shown in the chat.
                    SendResult.Ok, SendResult.Empty, SendResult.CommandError -> Unit
                }
            }
        },
        onInputReplaced = ::updateSuggestions,
    )

    val input: TextFieldValue get() = composer.input
    val replyTo: ChatItem? get() = composer.replyTo
    /** See [Composer.replyStarts]. */
    val replyStarts: Int get() = composer.replyStarts
    /** Pages with an unsent draft, marked next to their name. */
    val draftPages: Set<String> get() = composer.draftPages
    /** See [Composer.sendChannel]. */
    val sendChannel: String? get() = composer.sendChannel
    /** When the user may write in [sendChannel] again; 0 if now. */
    val sendWaitUntil: Long get() = composer.sendWaitUntil

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
     * The message a mention notification or the inbox points at. Cleared by its chat once it
     * scrolled there or found the message gone.
     */
    val requestedMessage = MutableStateFlow<MessageJump?>(null)

    /** Opens [channel] scrolled to the message [id]. */
    fun openMessage(channel: String, id: String) {
        requestedMessage.value = MessageJump(channel, id)
        requestedChannel.value = channel
    }

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
        viewModelScope.launch { c.chat.refused.collect(composer::onRefused) }

        // Android may end the process while Chatter is in the background; the drafts come back
        // with the window.
        savedState.get<Bundle>(DRAFTS_KEY)?.getString(DRAFTS_KEY)?.let(composer::restore)
        savedState.setSavedStateProvider(DRAFTS_KEY) { Bundle().apply { putString(DRAFTS_KEY, composer.save()) } }
        viewModelScope.launch {
            combine(c.auth.state, c.auth.accounts, ::Pair).collect { (state, accounts) ->
                if (state == AuthState.Loading) return@collect
                val active = (state as? AuthState.LoggedIn)?.account?.userId
                composer.useAccount(active, accounts.mapTo(HashSet()) { it.userId })
                updateSuggestions()
            }
        }
    }

    /**
     * The page this window shows: a channel or a combined chat key. Differs from [activePage] while
     * a bubble is open; that one belongs to the chat screen.
     */
    private val shownPage: String? get() = composer.shownPage

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

    /** The open catch-up summary; null while none is. */
    var catchUp by mutableStateOf<CatchUpSummary?>(null)
        private set
    private var catchUpJob: Job? = null

    /** Has Gemini Nano sum up [missed], the messages of [page] that arrived while away. */
    fun summarize(page: String, missed: List<ChatItem>) {
        catchUpJob?.cancel()
        val info = c.channels.info.value
        val name = c.channels.groups.value[page]?.displayName(info) ?: info[page]?.displayName ?: page
        catchUp = CatchUpSummary(name, missed.size)
        catchUpJob = viewModelScope.launch {
            try {
                c.onDeviceModel.summarize(
                    channel = name,
                    missed = missed,
                    combined = ChannelGroup.isKey(page),
                    language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH),
                    ownLogin = c.auth.account?.login,
                ).collect { catchUp = catchUp?.copy(text = it.text, leftOut = it.leftOut) }
                catchUp = catchUp?.copy(done = true)
            } catch (e: ModelFailure) {
                catchUp = catchUp?.copy(done = true, trouble = e.trouble)
            }
        }
    }

    fun closeCatchUp() {
        catchUpJob?.cancel()
        catchUp = null
    }

    /** Asks AICore again; after a system update or once the model has arrived. */
    fun prepareOnDeviceModel() {
        c.scope.launch { c.onDeviceModel.prepare() }
    }

    /** The channels a page reads from. */
    private fun channelsOf(page: String): List<String> =
        if (ChannelGroup.isKey(page)) groups.value[page]?.channels.orEmpty() else listOf(page)

    /**
     * Moves this window to [page]. Selecting the current page again reloads a combined chat whose
     * channels changed.
     */
    fun selectChannel(page: String?) {
        val channels = page?.let(::channelsOf).orEmpty()
        val left = shownPage
        if (!composer.show(page, channels)) return
        if (left != page) left?.let(::leave)
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
        suggestions = emptyList()
    }

    /** Writes in [channel] from now on; it must be one of the page's channels. */
    fun selectSendChannel(channel: String) {
        composer.selectSendChannel(channel)
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
        c.chat.windows.setVisible(this, visible, composer.shownChannels.toSet())
        // Leaving may come before the delay ends; then this is the last chance to write it.
        if (!visible) {
            writeLastChannel()
            shownPage?.let(::leave)
        }
        if (visible) {
            c.connect()
            composer.shownChannels.forEach {
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
        composer.input = value
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
        composer.input = TextFieldValue(text, TextRange(cursor))
        suggestions = emptyList()
    }

    fun insertEmote(emote: Emote) {
        val (text, cursor) = Autocomplete.insert(input.text, input.selection.start, emote.name)
        composer.input = TextFieldValue(text, TextRange(cursor))
        rememberEmote(emote.name)
    }

    fun insertEmoji(emoji: Emoji) {
        val (text, cursor) = Autocomplete.insert(input.text, input.selection.start, emoji.value)
        composer.input = TextFieldValue(text, TextRange(cursor))
        rememberEmote(emoji.value)
    }

    /** Every emoji the phone can draw, for the picker. */
    suspend fun emoji(): List<Emoji> = c.emoji.all()

    /**
     * Mentions the author of [item], in the channel it was written in on a combined chat. False if
     * there is no name or no input to put it in.
     */
    fun mention(item: ChatItem): Boolean {
        if (settings.value.readOnly) return false
        val name = item.displayName ?: item.login ?: return false
        selectSendChannel(item.channel)
        val (text, cursor) = Autocomplete.insert(input.text, input.selection.start, "@$name")
        composer.input = TextFieldValue(text, TextRange(cursor))
        return true
    }

    private fun rememberEmote(name: String) {
        saveSetting { addRecentEmote(name) }
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

    /** The newest messages in memory, for trying a rule on them in the editor. */
    suspend fun messagesToTryRulesOn(): List<ChatItem> = c.chat.newestMessages(RULE_TRY_MESSAGES)

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

    /** Reads the mentions of the channels on screen, as opening them does. */
    fun readShownChannels() {
        composer.shownChannels.forEach { viewModelScope.launch { c.inbox.markChannelRead(it) } }
    }

    /** Mentions currently on screen; one on screen is read, however it came to be unread. */
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

    /** [seconds] null pins until the stream ends. */
    fun pinMessage(item: ChatItem, seconds: Int?) {
        c.chat.runCommand(item.channel, ChatCommand.Pin(item.id, seconds))
    }

    fun unpinMessage(pin: PinnedMessage) {
        c.chat.runCommand(pin.item.channel, ChatCommand.Unpin(pin.item.id))
    }

    /** Keeps the pin of [channel] current while the caller runs it, e.g. while it is on screen. */
    suspend fun followPin(channel: String) = c.chat.pins.follow(channel)

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
     * own message before Twitch confirmed it, anything for a guest, or anything while the input is
     * hidden.
     */
    fun startReply(item: ChatItem): Boolean {
        if (c.auth.account == null || settings.value.readOnly) return false
        if (!item.canReply || item.id.startsWith("local-")) return false
        composer.startReply(item)
        updateSuggestions()
        return true
    }

    fun cancelReply() = composer.cancelReply()

    fun send() = composer.send()

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

    fun setUnreadInTitleBar(v: Boolean) = saveSetting { setUnreadInTitleBar(v) }
    fun setChannelTabs(v: Boolean) = saveSetting { setChannelTabs(v) }
    fun setHaptics(v: Boolean) = saveSetting { setHaptics(v) }
    fun setKeepScreenOn(v: Boolean) = saveSetting { setKeepScreenOn(v) }
    fun setKeepScreenOnWhenQuiet(v: Boolean) = saveSetting { setKeepScreenOnWhenQuiet(v) }
    fun setSeasonalAppIcons(v: Boolean) = saveSetting { setSeasonalAppIcons(v) }
    val appIcon: StateFlow<AppIcon> = c.appIcons.current
    fun pickAppIcon(icon: AppIcon) {
        viewModelScope.launch { c.appIcons.pick(icon) }
    }
    fun setBubbles(v: Boolean) = saveSetting { setBubbles(v) }
    fun setLiveNotifications(v: Boolean) = saveSetting { setLiveNotifications(v) }

    val liveAlerts = c.channels.liveAlerts

    fun setLiveAlert(login: String, enabled: Boolean) {
        viewModelScope.launch { c.channels.setLiveAlert(login, enabled) }
    }

    /** Every channel the account follows, for the live notification page; null without an answer. */
    suspend fun followedChannelList(): List<HelixFollowedChannel>? {
        val userId = c.auth.account?.userId ?: return null
        return runCatching { c.helix.followedChannels(userId) }.getOrNull()
    }

    /**
     * Opens a channel from a live notification. One that is not in the list is added, but only if
     * Chatter notified about it: any app can start Chatter with this extra.
     */
    fun openLiveChannel(login: String) {
        when {
            login in c.channels.channels.value -> requestedChannel.value = login
            c.liveAlerts.wasNotified(login) -> addChannel(login)
        }
    }

    fun setSenderAvatars(v: Boolean) = saveSetting { setSenderAvatars(v) }

    fun setBadgeProvider(provider: BadgeProvider, enabled: Boolean) {
        saveSetting { updateBadgeProviders { if (enabled) it + provider else it - provider } }
    }

    fun setEmoteProvider(provider: EmoteProvider, enabled: Boolean) {
        saveSetting { updateEmoteProviders { if (enabled) it + provider else it - provider } }
    }

    fun setTimestamps(v: TimestampFormat) = saveSetting { setTimestamps(v) }
    fun setMessageTap(v: TapAction) = saveSetting { setMessageTap(v) }
    fun setNameTap(v: TapAction) = saveSetting { setNameTap(v) }
    fun setCopyFirst(v: Boolean) = saveSetting { setCopyFirst(v) }
    fun setCatchUpSummary(v: Boolean) = saveSetting { setCatchUpSummary(v) }
    fun setAssistantActions(v: Boolean) = saveSetting { setAssistantActions(v) }
    fun setShowDeleted(v: Boolean) = saveSetting { setShowDeleted(v) }
    fun setEmoteSuggestions(v: Boolean) = saveSetting { setEmoteSuggestions(v) }
    fun setMentionWithAt(v: Boolean) = saveSetting { setMentionWithAt(v) }
    fun setReadOnly(v: Boolean) = saveSetting { setReadOnly(v) }
    fun setUserSuggestions(v: Boolean) = saveSetting { setUserSuggestions(v) }

    fun setChannelUnreadVisible(login: String, visible: Boolean) {
        viewModelScope.launch { c.channels.setUnreadVisible(login, visible) }
    }

    fun setChannelNotify(login: String, enabled: Boolean) {
        viewModelScope.launch { c.channels.setNotify(login, enabled) }
    }

    /** Loads the emotes again, those of [channel] and the ones usable everywhere; returns when done. */
    suspend fun reloadEmotes(channel: String?) {
        c.chat.reloadEmotes(listOfNotNull(channel)).join()
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

    // ---- Login / settings ----------------------------------------------------------------------

    /** Writes a setting off the main thread; the screen follows through [settings]. */
    private fun saveSetting(write: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { c.settings.write() }
    }

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
            // The profile journeys need the chat right away, even on a fresh install.
            c.settings.setOnboardingSeen(true)
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

    /** Refreshes the stored profiles of all accounts, unless that happened within the last day. */
    fun refreshAccounts() {
        viewModelScope.launch { c.auth.refreshProfilesIfStale() }
    }

    fun setFontSize(v: Float) = saveSetting { setFontSize(v) }
    fun setMessageLimit(v: Int) = saveSetting { setMessageLimit(v) }

    /** Adds highlight words. Commas split, so a pasted list becomes separate words. */
    fun addMentionKeyword(input: String) = saveSetting { updateMentionKeywords { withWords(it, input) ?: it } }

    fun removeMentionKeyword(word: String) {
        saveSetting { updateMentionKeywords { list -> list.filterNot { it.equals(word, ignoreCase = true) } } }
    }

    fun addMuteKeyword(input: String) = saveSetting { updateMuteKeywords { withWords(it, input) ?: it } }

    fun removeMuteKeyword(word: String) {
        saveSetting { updateMuteKeywords { list -> list.filterNot { it.equals(word, ignoreCase = true) } } }
    }

    /** The list with [input] added, or null if it holds nothing new. */
    private fun withWords(current: List<String>, input: String): List<String>? {
        val added = input.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && current.none { word -> word.equals(it, ignoreCase = true) } }
            .distinctBy { it.lowercase() }
        return if (added.isEmpty()) null else current + added
    }

    fun setThemeMode(v: ThemeMode) = saveSetting { setThemeMode(v) }
    fun setDynamicColor(v: Boolean) = saveSetting { setDynamicColor(v) }

    /** Reads the battery settings again, e.g. after the user changed them. */
    fun refreshBatteryRestrictions() = c.backgroundHealth.refresh()

    fun dismissBackgroundStop() = c.backgroundHealth.dismiss()

    fun setMobileData(v: MobileData) = saveSetting { setMobileData(v) }
    fun setPureBlack(v: Boolean) = saveSetting { setPureBlack(v) }
    fun setHighlightFirstMessages(v: Boolean) = saveSetting { setHighlightFirstMessages(v) }
    fun setSpecialMessage(kind: SpecialMessage, on: Boolean) = saveSetting { setSpecialMessage(kind, on) }
    fun setNameColors(v: NameColorPalette) = saveSetting { setNameColors(v) }
    fun setAlternateBackground(v: Boolean) = saveSetting { setAlternateBackground(v) }
    fun setHighlightColor(v: Int) = saveSetting { setHighlightColor(v) }
    fun setSmoothScrolling(v: Boolean) = saveSetting { setSmoothScrolling(v) }
    fun setInlineImages(v: Boolean) = saveSetting { setInlineImages(v) }
    fun setShowGifs(v: Boolean) = saveSetting { setShowGifs(v) }
    fun setPostSite(site: PostSite, on: Boolean) = saveSetting { setPostSite(site, on) }
    fun setBlurNsfwPosts(v: Boolean) = saveSetting { setBlurNsfwPosts(v) }
    fun setPostAvatar(v: Boolean) = saveSetting { setPostAvatar(v) }
    fun setPostDate(v: Boolean) = saveSetting { setPostDate(v) }
    fun setPostMedia(v: Boolean) = saveSetting { setPostMedia(v) }
    fun setPostCounts(v: Boolean) = saveSetting { setPostCounts(v) }
    fun setPostTextLines(v: Int) = saveSetting { setPostTextLines(v) }
    fun setPostPictureSize(v: PostPictureSize) = saveSetting { setPostPictureSize(v) }
    fun setFullLinks(v: Boolean) = saveSetting { setFullLinks(v) }

    /** Turns whatever was pasted into bare hosts. */
    fun addImageHost(input: String) {
        val added = input.split(',').map { ImageLinks.cleanHost(it) }.filter { it.isNotEmpty() }.distinct()
        if (added.isEmpty()) return
        saveSetting { updateImageHosts { current -> current + added.filter { it !in current } } }
    }

    fun removeImageHost(host: String) = saveSetting { updateImageHosts { it - host } }

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
    fun resetImageHosts() = saveSetting { updateImageHosts { ImageLinks.DEFAULT_HOSTS } }

    fun setCarouselChannels(v: Boolean) = saveSetting { setCarouselChannels(v) }
    fun setEmotesEnabled(v: Boolean) = saveSetting { setEmotesEnabled(v) }
    fun setZeroWidthEmotes(v: Boolean) = saveSetting { setZeroWidthEmotes(v) }
    fun setShowUnlisted7tv(v: Boolean) = saveSetting { setShowUnlisted7tv(v) }
    fun setSevenTvEvents(v: Boolean) = saveSetting { setSevenTvEvents(v) }
    fun setSevenTvPaints(v: Boolean) = saveSetting { setSevenTvPaints(v) }
    fun setLoadHistory(v: Boolean) = saveSetting { setLoadHistory(v) }
    fun setAnimatedEmotes(v: Boolean) = saveSetting { setAnimatedEmotes(v) }
    fun setSlowIdleEmotes(v: Boolean) = saveSetting { setSlowIdleEmotes(v) }

    fun resetStats() {
        viewModelScope.launch { c.stats.reset() }
    }

    /** The update notes were seen and should not come back. */
    fun markChangelogRead() = c.changelog.markRead()

    fun markNotificationIntroSeen() = saveSetting { setNotificationIntroSeen() }

    fun finishOnboarding() = saveSetting { setOnboardingSeen(true) }

    /** For the developer page: the onboarding comes up again as on a fresh install. */
    fun showOnboardingAgain() = saveSetting { setOnboardingSeen(false) }

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

        /** Where the drafts are kept when Android saves the window. */
        const val DRAFTS_KEY = "drafts"

        /** Enough to show what a rule finds in a busy chat, few enough to try on every keystroke. */
        const val RULE_TRY_MESSAGES = 300
    }
}
