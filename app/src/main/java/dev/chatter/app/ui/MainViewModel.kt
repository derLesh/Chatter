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
import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.SendResult
import dev.chatter.app.emotes.Emote
import dev.chatter.app.net.HelixChannelSearch
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

sealed interface Suggestion {
    data class EmoteSuggestion(val emote: Emote) : Suggestion
    data class UserSuggestion(val name: String) : Suggestion
}

class MainViewModel(private val c: AppContainer) : ViewModel() {
    val authState = c.auth.state
    val channels = c.channels.channels
    val channelInfo = c.channels.info
    val unreadMentions = c.chat.unreadMentions
    val settings = c.settings.settings
    val connection = c.irc.state
    val activeChannel = c.chat.activeChannel
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
            suggestions = if (word.text.startsWith("@")) {
                Autocomplete.rankUsers(word.text, c.chat.chatters(channel)).map { Suggestion.UserSuggestion(it) }
            } else if (word.text.length >= 2) {
                Autocomplete.rankEmotes(word.text, c.emotes.available(c.chat.roomId(channel)))
                    .map { Suggestion.EmoteSuggestion(it) }
            } else emptyList()
        }
    }

    fun applySuggestion(s: Suggestion) {
        val word = Autocomplete.currentWord(input.text, input.selection.start) ?: return
        val value = when (s) {
            is Suggestion.EmoteSuggestion -> s.emote.name.also { rememberEmote(it) }
            is Suggestion.UserSuggestion -> "@${s.name}"
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

    fun emotesFor(channel: String?): List<Emote> = c.emotes.available(channel?.let { c.chat.roomId(it) })

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
                SendResult.UnsupportedCommand -> _messages.send(R.string.error_unsupported_command)
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

    fun setShowTimestamps(v: Boolean) {
        viewModelScope.launch { c.settings.setShowTimestamps(v) }
    }

    fun setMessageLimit(v: Int) {
        viewModelScope.launch { c.settings.setMessageLimit(v) }
    }

    fun setMentionKeywords(v: String) {
        viewModelScope.launch { c.settings.setMentionKeywords(v) }
    }

    fun setAnimatedEmotes(v: Boolean) {
        viewModelScope.launch { c.settings.setAnimatedEmotes(v) }
    }
}
