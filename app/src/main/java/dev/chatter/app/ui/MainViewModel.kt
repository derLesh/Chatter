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
import dev.chatter.app.auth.DeviceLogin
import dev.chatter.app.badges.Badge
import dev.chatter.app.chat.ChatCommand
import dev.chatter.app.chat.ChatRole
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.CommandParser
import dev.chatter.app.chat.SendResult
import dev.chatter.app.emotes.Emote
import dev.chatter.app.emotes.EmoteProvider
import dev.chatter.app.net.HelixChannelSearch
import dev.chatter.app.net.HelixUser
import dev.chatter.app.settings.ThemeMode
import dev.chatter.app.ui.theme.NameColorPalette
import dev.chatter.app.settings.TimestampFormat
import dev.chatter.app.util.Autocomplete
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface LoginUi {
    data object Idle : LoginUi
    data object Starting : LoginUi
    data class Waiting(val device: DeviceLogin) : LoginUi
    data class Failed(val message: String) : LoginUi
}

data class UserCardData(val user: HelixUser?, val recentMessages: List<ChatItem>)

sealed interface Suggestion {
    data class EmoteSuggestion(val emote: Emote) : Suggestion
    data class UserSuggestion(val name: String) : Suggestion
    data class CommandSuggestion(val name: String, val usage: String) : Suggestion
}

class MainViewModel(private val c: AppContainer) : ViewModel() {
    val authState = c.auth.state
    val channels = c.channels.channels
    val channelInfo = c.channels.info
    val customNames = c.channels.customNames
    val mutedChannels = c.channels.mutedChannels
    val hiddenUnread = c.channels.hiddenUnread
    val unreadMentions = c.chat.unreadMentions
    val unreadMessages = c.chat.unreadMessages
    val settings = c.settings.settings
    val connection = c.irc.state
    val activeChannel = c.chat.activeChannel
    val modChannels = c.chat.modChannels
    val roomStates = c.chat.roomStates
    val roles = c.chat.roles
    val emoteVersion = c.emotes.version

    val imageLoader get() = c.imageLoader
    val staticImageLoader get() = c.staticImageLoader

    var input by mutableStateOf(TextFieldValue(""))
        private set
    var replyTo by mutableStateOf<ChatItem?>(null)
        private set
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set

    /** Channel requested from outside (notification tap) that the pager should scroll to. */
    val requestedChannel = MutableStateFlow<String?>(null)

    private val _messages = Channel<Int>(Channel.BUFFERED)
    /** One-off user feedback as string resource ids (shown as snackbar). */
    val messages = _messages.receiveAsFlow()

    private var suggestionJob: Job? = null

    fun chat(channel: String) = c.chat.messages(channel)

    fun selectChannel(channel: String?) {
        if (c.chat.activeChannel.value == channel) return
        c.chat.activeChannel.value = channel
        channel?.let { c.notifier.clear(it) }
        replyTo = null
        suggestions = emptyList()
    }

    fun setUiVisible(visible: Boolean) {
        c.chat.uiVisible.value = visible
        if (visible) {
            c.connect()
            activeChannel.value?.let { c.chat.clearUnread(it); c.notifier.clear(it) }
        }
    }

    // ---- Input & autocomplete --------------------------------------------------------------

    fun onInputChange(value: TextFieldValue) {
        input = value
        updateSuggestions()
    }

    private fun updateSuggestions() {
        suggestionJob?.cancel()
        val channel = activeChannel.value
        val word = Autocomplete.currentWord(input.text, input.selection.start)
        if (channel == null || word == null) {
            suggestions = emptyList()
            return
        }
        suggestionJob = viewModelScope.launch {
            suggestions = if (word.start == 0 && word.text.startsWith("/")) {
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
        }
    }

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

    /** Emotes the user can type in [channel]; unlisted 7TV emotes only if enabled in settings. */
    fun emotesFor(channel: String?): List<Emote> {
        val s = settings.value
        return c.emotes.available(channel?.let { c.chat.roomId(it) })
            .filter { it.provider in s.emoteProviders }
            .filterNot { !s.showUnlisted7tv && it.unlisted }
    }

    /** Profile (may be null if Twitch is unreachable) plus the user's recent messages here. */
    suspend fun loadUserCard(item: ChatItem): UserCardData {
        val login = item.login ?: return UserCardData(null, emptyList())
        val recent = c.chat.messagesFrom(item.channel, login)
        val user = runCatching { c.helix.users(listOf(login)).firstOrNull() }.getOrNull()
        return UserCardData(user, recent)
    }

    /** Twitch badge image for the user's role in a channel (moderator sword etc.). */
    fun roleBadge(channel: String, role: ChatRole): Badge? =
        role.badgeTag?.let { c.badges.resolve(c.chat.roomId(channel), it).firstOrNull() }

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

    fun startReply(item: ChatItem) {
        replyTo = item
    }

    fun cancelReply() {
        replyTo = null
    }

    fun send() {
        val channel = activeChannel.value ?: return
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
                SendResult.NotConnected -> _messages.send(R.string.error_not_connected)
                SendResult.RateLimited -> _messages.send(R.string.error_rate_limited)
                SendResult.CommandError -> Unit // the hint is shown in the chat
            }
        }
    }

    // ---- Channels ----------------------------------------------------------------------------

    fun addChannel(name: String) {
        viewModelScope.launch {
            val login = c.channels.add(name)
            if (login == null) _messages.send(R.string.error_invalid_channel)
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

    fun setKeepScreenOn(v: Boolean) {
        viewModelScope.launch { c.settings.setKeepScreenOn(v) }
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

    var login by mutableStateOf<LoginUi>(LoginUi.Idle)
        private set
    private var loginJob: Job? = null

    /** Gets a code from Twitch and waits (in the ViewModel, so it survives rotation) for confirmation. */
    fun startLogin() {
        loginJob?.cancel()
        login = LoginUi.Starting
        loginJob = viewModelScope.launch {
            val device = try {
                c.auth.startDeviceLogin()
            } catch (e: Exception) {
                login = LoginUi.Failed(e.message ?: e.toString())
                return@launch
            }
            login = LoginUi.Waiting(device)
            c.auth.awaitDeviceLogin(device)
                .onSuccess { login = LoginUi.Idle }
                .onFailure { login = LoginUi.Failed(it.message ?: it.toString()) }
        }
    }

    fun loginUrl(): String = c.auth.authorizeUrl()

    suspend fun handleRedirect(url: String): Result<Unit>? = c.auth.handleRedirect(url)

    fun cancelLogin() {
        loginJob?.cancel()
        login = LoginUi.Idle
    }

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

    fun setMentionKeywords(v: String) {
        viewModelScope.launch { c.settings.setMentionKeywords(v) }
    }

    fun setThemeMode(v: ThemeMode) {
        viewModelScope.launch { c.settings.setThemeMode(v) }
    }

    fun setDynamicColor(v: Boolean) {
        viewModelScope.launch { c.settings.setDynamicColor(v) }
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
}
