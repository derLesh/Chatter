package dev.chatter.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chatter.app.AppContainer
import dev.chatter.app.R
import dev.chatter.app.badges.Badge
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.chat.ChatCommand
import dev.chatter.app.chat.ChatRole
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.CommandParser
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.chat.InboxMention
import dev.chatter.app.chat.InboxWhisper
import dev.chatter.app.chat.SendResult
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.net.HelixChannelSearch
import dev.chatter.app.net.HelixBlockedUser
import dev.chatter.app.net.HelixUser
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.stats.Stats
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.settings.TapAction
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.util.Autocomplete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A one-off line for the snackbar: the text, and what it has to have filled in. The filling in
 * happens on the screen and not here, so that a message already up follows a change of language.
 */
data class UiMessage(val text: Int, val fill: String? = null)

sealed interface Suggestion {
    data class EmoteSuggestion(val emote: Emote) : Suggestion
    data class UserSuggestion(val name: String) : Suggestion
    data class CommandSuggestion(val name: String, val usage: String) : Suggestion
}

class MainViewModel(private val c: AppContainer) : ViewModel() {
    val authState = c.auth.state

    /** Whether the last login ended on its own, so the login screen can say why it is back. */
    val sessionExpired = c.auth.sessionExpired

    /** Who the user is on Twitch, for the one thing GitHub Sponsors cannot know about a sponsor. */
    val ownTwitchId: String? get() = c.auth.account?.userId
    val ownLogin: String get() = c.auth.account?.login.orEmpty()
    val channels = c.channels.channels
    val channelInfo = c.channels.info
    val customNames = c.channels.customNames
    val mutedChannels = c.channels.mutedChannels
    val hiddenUnread = c.channels.hiddenUnread
    val lastChannel = c.channels.lastChannel
    val unreadMentions = c.chat.unreadMentions
    val unreadMessages = c.chat.unreadMessages
    val settings = c.settings.settings
    val connection = c.irc.state
    val activeChannel = c.chat.activeChannel
    val modChannels = c.chat.rooms.moderated
    val powerSaveMode = c.powerSaveMode
    val roomStates = c.chat.rooms.states
    val roles = c.chat.rooms.roles
    val emoteVersion = c.emotes.version
    val blockedUsers = c.blocked.blocked
    val blockedLogins = c.blocked.logins
    val nicknames = c.nicknames.nicknames
    val rules = c.rules.rules
    val inboxMentions = c.inbox.mentions
    val inboxWhispers = c.whisperInbox.whispers
    val mentionUnread = c.inbox.unreadCount
    val whisperUnread = c.whisperInbox.unreadCount

    /** What the badge on the inbox button counts: both of its tabs together. */
    val inboxUnread: StateFlow<Int> = combine(mentionUnread, whisperUnread) { m, w -> m + w }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    /**
     * The counters, worked out only while the page showing them is open: away from it they are
     * two numbers being added up, which is what a message arriving should cost.
     */
    val stats: StateFlow<Stats> = c.stats.live()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), c.stats.stats.value)
    val releases = c.changelog.releases
    /** Every mention as it arrives, for the feedback the chat screen gives while it is open. */
    val mentions = c.chat.allMentions
    /** The releases the user has not read yet, shown once after an update. */
    val unreadReleases = c.changelog.unread

    val imageLoader get() = c.imageLoader
    val staticImageLoader get() = c.staticImageLoader

    var input by mutableStateOf(TextFieldValue(""))
        private set
    var replyTo by mutableStateOf<ChatItem?>(null)
        private set
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set

    /**
     * True for the view model behind a chat bubble. A bubble lives inside its notification, so
     * cancelling that notification would take the bubble down with it — which is why a bubble
     * never clears one, however much of the channel the user reads in it.
     */
    var inBubble = false

    /** Channel requested from outside (notification tap) that the pager should scroll to. */
    val requestedChannel = MutableStateFlow<String?>(null)

    /**
     * The inbox tab something outside the app asked for (its shortcut, a whisper notification),
     * or null when nothing did. Cleared by the inbox once it has gone there.
     */
    val requestedInbox = MutableStateFlow<Int?>(null)

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    /** One-off user feedback, shown as a snackbar. */
    val messages = _messages.receiveAsFlow()

    private var suggestionJob: Job? = null

    init {
        // Something outside the app did not answer. Said once, and then not again.
        viewModelScope.launch {
            c.trouble.unreachable.collect { _messages.send(UiMessage(R.string.error_service_down, it)) }
        }
    }

    /**
     * The channel this window is showing. Not the same thing as [activeChannel] once a bubble is
     * open: that one is the chat screen's, this one is whatever window this view model belongs to.
     */
    private var shownChannel: String? = null

    fun chat(channel: String) = c.chat.messages(channel)

    fun selectChannel(channel: String?) {
        if (shownChannel == channel) return
        shownChannel = channel
        c.chat.windows.setChannel(this, channel)
        // What the app around the chat follows. A bubble is a window of its own and must not
        // move it: the chat screen is still wherever the user left it.
        if (!inBubble) c.chat.activeChannel.value = channel
        channel?.let {
            if (!inBubble) c.notifier.clear(it)
            c.chat.clearUnread(it)
            // Where to come back to after a restart — the chat screen's channel, not one the user
            // happens to be reading in a bubble on the side.
            if (!inBubble) rememberLastChannel(it)
            // Reading a channel is reading its mentions, so the inbox must not claim otherwise.
            viewModelScope.launch { c.inbox.markChannelRead(it) }
        }
        replyTo = null
        suggestions = emptyList()
    }

    /**
     * Tells the chat that the whisper tab is in front, which is what keeps a whisper arriving
     * there from also ringing.
     */
    fun setWhispersVisible(visible: Boolean) {
        c.chat.windows.whispersVisible.value = visible
        if (visible) c.notifier.clearWhispers()
    }

    /**
     * Writing down where to come back to, once the swiping has settled. Every write is a file
     * rewritten and every flow on that store parsed again, which is a lot of ceremony for a
     * channel the user is only passing through on the way to the next one.
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
        c.chat.windows.setVisible(this, visible, shownChannel)
        // Leaving may come before the delay is up, and then it is the last chance to write it.
        if (!visible) writeLastChannel()
        if (visible) {
            c.connect()
            shownChannel?.let {
                c.chat.clearUnread(it)
                if (!inBubble) c.notifier.clear(it)
            }
        }
    }

    /** The window is gone for good; it is not reading anything any more. */
    override fun onCleared() {
        c.chat.windows.setVisible(this, visible = false, channel = null)
        super.onCleared()
    }

    // ---- Input & autocomplete --------------------------------------------------------------

    fun onInputChange(value: TextFieldValue) {
        input = value
        updateSuggestions()
    }

    private fun updateSuggestions() {
        suggestionJob?.cancel()
        val channel = shownChannel
        val word = Autocomplete.currentWord(input.text, input.selection.start)
        if (channel == null || word == null) {
            suggestions = emptyList()
            return
        }
        // Off the main thread: ranking runs on every keystroke and reads through every emote the
        // user has, which with a well subscribed account is a few thousand of them.
        suggestionJob = viewModelScope.launch {
            suggestions = withContext(Dispatchers.Default) { rank(channel, word) }
        }
    }

    private suspend fun rank(channel: String, word: Autocomplete.Word): List<Suggestion> =
            if (word.start == 0 && word.text.startsWith("/")) {
                val typed = word.text.substring(1).lowercase()
                CommandParser.COMMANDS.filterKeys { it.startsWith(typed) }
                    .map { (name, usage) -> Suggestion.CommandSuggestion(name, usage) }
            } else if (word.text.startsWith("@")) {
                if (!settings.value.userSuggestions) emptyList()
                else Autocomplete.rankUsers(word.text, c.chat.chatters(channel)).map { Suggestion.UserSuggestion(it) }
            } else if (word.text.length >= 2) {
                val s = settings.value
                // A plain word can be either, so offer both: emotes first, as they are what one
                // usually types without an "@", with the names behind them.
                val emotes = if (!s.emoteSuggestions) emptyList() else {
                    Autocomplete.rankEmotes(word.text, emotesFor(channel)).map { Suggestion.EmoteSuggestion(it) }
                }
                val users = if (!s.userSuggestions) emptyList() else {
                    Autocomplete.rankUsers(word.text, c.chat.chatters(channel), limit = 15)
                        .map { Suggestion.UserSuggestion(it) }
                }
                emotes + users
            } else emptyList()

    fun applySuggestion(s: Suggestion) {
        val word = Autocomplete.currentWord(input.text, input.selection.start) ?: return
        val value = when (s) {
            is Suggestion.EmoteSuggestion -> s.emote.name.also { rememberEmote(it) }
            // An "@" the user typed themselves is kept either way: they asked for it.
            is Suggestion.UserSuggestion ->
                if (settings.value.mentionWithAt || word.text.startsWith("@")) "@${s.name}" else s.name
            is Suggestion.CommandSuggestion -> "/${s.name}"
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

    fun mention(item: ChatItem) {
        val name = item.displayName ?: item.login ?: return
        val (text, cursor) = Autocomplete.insert(input.text, input.selection.start, "@$name")
        input = TextFieldValue(text, TextRange(cursor))
    }

    private fun rememberEmote(name: String) {
        viewModelScope.launch { c.settings.addRecentEmote(name) }
    }

    /** What [emotesFor] last worked out, and what it was worked out from. */
    private class EmoteList(
        val channel: String?,
        val version: Int,
        val providers: Set<EmoteProvider>,
        val unlisted: Boolean,
        val emotes: List<Emote>,
    )

    @Volatile private var lastEmoteList: EmoteList? = null

    /**
     * Emotes the user can type in [channel]; unlisted 7TV emotes only if enabled in settings.
     *
     * Kept until something about it changes. Building it walks every global, channel and Twitch
     * emote the account has, and the autocomplete asks for it on every keystroke.
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

    /** What the writer of [item] said in its channel lately, oldest first. Read from memory. */
    suspend fun recentMessagesOf(item: ChatItem): List<ChatItem> {
        val login = item.login ?: return emptyList()
        return c.chat.messagesFrom(item.channel, login)
    }

    /** The Twitch profile of whoever wrote [item]; null if Twitch is unreachable. */
    suspend fun profileOf(item: ChatItem): HelixUser? {
        val login = item.login ?: return null
        return runCatching { c.helix.users(listOf(login)).firstOrNull() }.getOrNull()
    }

    /** Twitch badge image for the user's role in a channel (moderator sword etc.). */
    fun roleBadge(channel: String, role: ChatRole): Badge? =
        role.badgeTag?.let { c.badges.resolve(c.chat.rooms.id(channel), it, userId = null).firstOrNull() }

    /**
     * Blocks or unblocks on Twitch. Needs the user card's profile for the Twitch id, so it is
     * only offered once that has loaded.
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

    /**
     * Blocks someone typed by name rather than picked from a chat message, so the login has to be
     * looked up first: Twitch only takes ids.
     */
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

    /** The whole configuration as a Chatter backup file. */
    fun exportBackup(): String = c.backup.export()

    /** Restores a backup. False means the file was not one of ours. */
    suspend fun importBackup(text: String): Boolean = c.backup.import(text)

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

    fun markInboxRead() {
        viewModelScope.launch { c.inbox.markAllRead() }
    }

    fun clearInbox() {
        viewModelScope.launch { c.inbox.clear() }
    }

    /** Answers a whisper. Returns the sentence to show about it, sent or not. */
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

    /** Gives a chatter a nickname, in every channel they show up in. A blank one clears it. */
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
     * Answers [item] with the next message sent. False for one that cannot be answered: a notice,
     * or the user's own message before Twitch has said what it is called.
     */
    fun startReply(item: ChatItem): Boolean {
        if (!item.canReply || item.id.startsWith("local-")) return false
        replyTo = item
        return true
    }

    fun cancelReply() {
        replyTo = null
    }

    fun send() {
        val channel = shownChannel ?: return
        val text = input.text
        val reply = replyTo
        viewModelScope.launch {
            when (c.chat.send(channel, text, reply)) {
                SendResult.Ok -> {
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

    fun removeChannel(login: String) {
        viewModelScope.launch { c.channels.remove(login) }
    }

    fun setUnreadInTitleBar(v: Boolean) {
        viewModelScope.launch { c.settings.setUnreadInTitleBar(v) }
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
        val current = settings.value.badgeProviders
        viewModelScope.launch {
            c.settings.setBadgeProviders(if (enabled) current + provider else current - provider)
        }
    }

    fun setEmoteProvider(provider: EmoteProvider, enabled: Boolean) {
        val current = settings.value.emoteProviders
        viewModelScope.launch {
            c.settings.setEmoteProviders(if (enabled) current + provider else current - provider)
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

    /** The name Twitch reports, for showing what clearing a custom name restores. */
    fun twitchName(login: String): String = c.channels.twitchName(login)

    fun moveChannel(login: String, delta: Int) {
        viewModelScope.launch { c.channels.move(login, delta) }
    }

    suspend fun searchChannels(query: String): List<HelixChannelSearch> = c.channels.search(query)

    /** Polls live status while the UI is visible. Cancelled automatically when it goes away. */
    suspend fun pollLiveStatus() {
        while (true) {
            c.channels.refreshLive()
            delay(120_000)
        }
    }

    // ---- Login / settings ----------------------------------------------------------------------

    fun loginUrl(): String = c.auth.authorizeUrl()

    suspend fun handleRedirect(url: String): Result<Unit>? = c.auth.handleRedirect(url)

    fun logout() {
        viewModelScope.launch {
            c.disconnect()
            c.auth.logout()
        }
    }

    fun setFontSize(v: Float) {
        viewModelScope.launch { c.settings.setFontSize(v) }
    }

    fun setMessageLimit(v: Int) {
        viewModelScope.launch { c.settings.setMessageLimit(v) }
    }

    /**
     * Adds a word to highlight on. A comma still splits, so a list pasted in one go lands as
     * separate words instead of one unmatchable one.
     */
    fun addMentionKeyword(input: String) {
        val next = withWords(settings.value.mentionKeywords, input) ?: return
        viewModelScope.launch { c.settings.setMentionKeywords(next) }
    }

    fun removeMentionKeyword(word: String) {
        viewModelScope.launch {
            c.settings.setMentionKeywords(settings.value.mentionKeywords.filterNot { it.equals(word, ignoreCase = true) })
        }
    }

    fun addMuteKeyword(input: String) {
        val next = withWords(settings.value.muteKeywords, input) ?: return
        viewModelScope.launch { c.settings.setMuteKeywords(next) }
    }

    fun removeMuteKeyword(word: String) {
        viewModelScope.launch {
            c.settings.setMuteKeywords(settings.value.muteKeywords.filterNot { it.equals(word, ignoreCase = true) })
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

    /** Whatever they pasted, turned into the bare host it names. */
    fun addImageHost(input: String) {
        val current = settings.value.imageHosts
        val added = input.split(',').map { ImageLinks.cleanHost(it) }
            .filter { it.isNotEmpty() && it !in current }
            .distinct()
        if (added.isEmpty()) return
        viewModelScope.launch { c.settings.setImageHosts(current + added) }
    }

    fun removeImageHost(host: String) {
        viewModelScope.launch { c.settings.setImageHosts(settings.value.imageHosts - host) }
    }

    /** [old] changed in place, so the list keeps the order the user put it in. */
    fun editImageHost(old: String, input: String) {
        val host = ImageLinks.cleanHost(input)
        val current = settings.value.imageHosts
        val at = current.indexOf(old)
        if (host.isEmpty() || host == old || at < 0) return
        // Already further down the list: changing this one into it would only say it twice.
        val next = if (host in current) current - old else current.toMutableList().also { it[at] = host }
        viewModelScope.launch { c.settings.setImageHosts(next) }
    }

    /** Back to the hosts a fresh install trusts, for a list that was pruned too far. */
    fun resetImageHosts() {
        viewModelScope.launch { c.settings.setImageHosts(ImageLinks.DEFAULT_HOSTS) }
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

    fun resetStats() {
        viewModelScope.launch { c.stats.reset() }
    }

    /** The update notes have been seen, so they should not come back. */
    fun markChangelogRead() = c.changelog.markRead()

    private companion object {
        /** How long a channel has to stay on screen before it is remembered as the last one. */
        const val LAST_CHANNEL_DELAY_MS = 1_500L
    }
}
