package dev.chatter.app.chat

import android.content.Context
import android.util.Log
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.badges.BadgeRepository
import dev.chatter.app.channels.BlockedUsersRepository
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.emotes.EmoteLoadFailure
import dev.chatter.app.emotes.EmoteRepository
import dev.chatter.app.emotes.label
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.ThirdPartyApi
import dev.chatter.app.settings.Settings
import dev.chatter.app.util.RateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A mention that just arrived, and whether the user had that channel open at the time. */
data class MentionEvent(val item: ChatItem, val seen: Boolean)

enum class SendResult {
    Ok, Empty, NotConnected, RateLimited,
    /** A command was not executed (unknown / wrong usage); the hint is shown in the chat. */
    CommandError,
}

/**
 * Owns the message buffers of all channels.
 *
 * Every mutation runs on one single-threaded dispatcher ([worker]), so no locks are needed.
 * The messages themselves live in [MessageBuffers], which is also what hands them to the
 * screen; this class is about what Twitch says and what the app makes of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(
    private val context: Context,
    private val irc: ChatConnection,
    private val builder: MessageBuilder,
    private val emotes: EmoteRepository,
    private val badges: BadgeRepository,
    private val channelRepo: ChannelRepository,
    private val thirdParty: ThirdPartyApi,
    private val helix: HelixApi,
    private val auth: AuthRepository,
    private val commands: CommandExecutor,
    private val notices: ChatNotices,
    private val chatterRegistry: ChatterRegistry,
    private val blocked: BlockedUsersRepository,
    private val stats: ChatStats,
    private val rules: StateFlow<List<ChatRule>>,
    private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
) {
    private val worker = Dispatchers.Default.limitedParallelism(1)

    /** The messages themselves, and how they reach the screen. */
    private val buffers = MessageBuffers(scope, worker, settings)

    /** What Twitch says about the channels and the user's place in them; see [Rooms]. */
    val rooms = Rooms()

    /** Which windows are on screen and what each of them shows; see [ChatWindows]. */
    val windows = ChatWindows()

    // --- state touched only on `worker` ---
    private val lastSent = HashMap<String, Pair<String, Long>>()
    private val rateLimiter = RateLimiter(30_000)
    private val loadedChannels = HashSet<String>()
    private var joinedChannels: List<String> = emptyList()

    /**
     * What the settings make of a message as it arrives. The rules are applied on arrival, so
     * changing one paints what comes next — the messages already on screen stay as they were read.
     */
    @Volatile private var filters = ChatFilters()

    /** What Twitch says, and what the app makes of it; see [IncomingMessages]. */
    private val incoming = IncomingMessages(
        builder = builder,
        buffers = buffers,
        rooms = rooms,
        windows = windows,
        chatters = chatterRegistry,
        notices = notices,
        stats = stats,
        filters = { filters },
        selfLogin = { auth.account?.login.orEmpty() },
        onMention = ::onMention,
        onWhisper = ::onWhisper,
        onRoomFound = { channelId -> scope.launch { loadEmotesAndBadges(channelId) } },
    )

    private val _mentionEvents = MutableSharedFlow<ChatItem>(extraBufferCapacity = 32)
    /** Emitted for live (non-history) messages that mention the user in a channel they are not looking at. */
    val mentionEvents: SharedFlow<ChatItem> = _mentionEvents

    private val _allMentions = MutableSharedFlow<MentionEvent>(extraBufferCapacity = 32)
    /** Every live mention, including the ones the user is watching happen. For the inbox. */
    val allMentions: SharedFlow<MentionEvent> = _allMentions

    private val _whispers = MutableSharedFlow<InboxWhisper>(extraBufferCapacity = 16)
    /**
     * Whispers, which belong to no channel and so have nowhere else to go. Twitch only delivers
     * them when the token carries the `whispers:read` scope, which older logins do not have.
     */
    val whispers: SharedFlow<InboxWhisper> = _whispers

    private val _whisperEvents = MutableSharedFlow<InboxWhisper>(extraBufferCapacity = 16)
    /** The whispers that arrived while nobody was looking at them, for the notification. */
    val whisperEvents: SharedFlow<InboxWhisper> = _whisperEvents

    private val _unreadMentions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMentions: StateFlow<Map<String, Int>> = _unreadMentions

    /** New messages per channel since the user last looked at it. */
    val unreadMessages: StateFlow<Map<String, Int>> get() = buffers.unreadMessages

    /** The channel the chat screen is on. A bubble reads its own and leaves this alone. */
    val activeChannel = MutableStateFlow<String?>(null)

    fun start() {
        scope.launch(worker) { irc.messages.collect { incoming.handle(it) } }

        scope.launch(worker) {
            var showedDeleted = settings.value.showDeleted
            combine(auth.state, settings, blocked.logins) { _, s, blockedLogins -> s to blockedLogins }
                .collect { (s, blockedLogins) ->
                    val muted = MuteFilter(s.muteKeywords, blockedLogins)
                    filters = filters.copy(
                        mentions = MentionMatcher(auth.account?.login.orEmpty(), s.mentionKeywords),
                        muted = muted,
                    )
                    buffers.dropMuted(muted)
                    buffers.trimAll(s.messageLimit)
                    // Deleted messages are filtered out when publishing, so turning them back on
                    // has to republish what is already buffered.
                    if (s.showDeleted != showedDeleted) {
                        showedDeleted = s.showDeleted
                        buffers.republishAll()
                    }
                }
        }

        scope.launch(worker) {
            rules.collect { filters = filters.copy(rules = RuleEngine(it)) }
        }

        scope.launch(worker) {
            channelRepo.channels.collect { syncChannels(it) }
        }

        scope.launch(worker) {
            emotes.failures.collect { reportEmoteFailure(it) }
        }

        scope.launch(worker) {
            var wasConnected = false
            irc.state.collect { state ->
                when (state) {
                    ConnectionState.Connected -> if (wasConnected) {
                        systemAll(R.string.chat_reconnected)
                        // Fetch what was said while we were offline.
                        buffers.channels().forEach { ch -> scope.launch { loadHistory(ch, since = incoming.lastLive(ch)) } }
                    } else wasConnected = true
                    ConnectionState.Connecting -> if (wasConnected) systemAll(R.string.chat_disconnected)
                    else -> Unit
                }
            }
        }


    }

    fun messages(channel: String): StateFlow<List<ChatItem>> = buffers.messages(channel)

    /** Adds an informational line (e.g. 7TV activity), optionally followed by emotes/text segments. */
    fun postNotice(channel: String, text: String, segments: List<Segment> = emptyList()) = scope.launch(worker) {
        buffers.add(
            ChatItem(id = UUID.randomUUID().toString(), channel = channel, kind = MessageKind.Notice,
                timestamp = System.currentTimeMillis(), systemText = text, text = text,
                body = MessageBody.of(segments))
        )
    }

    fun clearUnread(channel: String) {
        _unreadMentions.update { it - channel }
        scope.launch(worker) { buffers.clearUnread(channel) }
    }

    /** The latest messages of one user in a channel (oldest first), for the user card. */
    suspend fun messagesFrom(channel: String, login: String, limit: Int = 30): List<ChatItem> =
        buffers.from(channel, login, limit)

    /** Display names of recently active chatters, most recent first. */
    suspend fun chatters(channel: String): List<String> = withContext(worker) {
        chatterRegistry.names(channel)
    }

    suspend fun send(channel: String, input: String, replyTo: ChatItem?): SendResult = withContext(worker) {
        var text = input.trim()
        if (text.isEmpty()) return@withContext SendResult.Empty
        CommandParser.parse(text)?.let { command ->
            system(channel, commands.execute(command, rooms.id(channel)))
            val invalid = command is ChatCommand.Usage || command is ChatCommand.Unknown
            return@withContext if (invalid) SendResult.CommandError else SendResult.Ok
        }

        val now = System.currentTimeMillis()
        // Twitch drops identical consecutive messages; an invisible tag character avoids that.
        lastSent[channel]?.let { (prev, at) -> if (prev == text && now - at < 30_000) text += DUPLICATE_BYPASS }

        val state = rooms.userState(channel)
        if (!rateLimiter.tryAcquire(now, if (rooms.isPrivileged(channel)) 100 else 20)) {
            return@withContext SendResult.RateLimited
        }

        val wire = if (text.startsWith("/me ")) "$CTCP_ACTION${text.substring(4)}$CTCP_END" else text
        val replyParent = replyTo?.takeIf { it.canReply && !it.id.startsWith("local-") }
        if (!irc.sendMessage(channel, wire, replyParent?.id)) return@withContext SendResult.NotConnected

        lastSent[channel] = input.trim() to now
        val reply = replyParent?.let {
            ReplyInfo(it.id, it.login.orEmpty(), it.displayName.orEmpty(), it.text)
        }
        buffers.add(builder.buildOwn(channel, wire, state, auth.account?.login.orEmpty(), rooms.id(channel), reply))
        stats.countSent(channel)
        SendResult.Ok
    }

    /** Runs a moderation command (e.g. from the message actions) and reports the result in the chat. */
    fun runCommand(channel: String, command: ChatCommand) = scope.launch(worker) {
        system(channel, commands.execute(command, rooms.id(channel)))
    }

    /** Drops all buffers, e.g. after logout. */
    fun reset() = scope.launch(worker) {
        buffers.clearAll()
        chatterRegistry.clear()
        rooms.clear()
        incoming.clear()
        loadedChannels.clear()
        joinedChannels = emptyList()
        _unreadMentions.value = emptyMap()
    }

    /** Joins all channels of the list again. Called after login. */
    fun resync() = scope.launch(worker) {
        joinedChannels = emptyList()
        syncChannels(channelRepo.channels.value)
    }

    // ---------------------------------------------------------------------------------------

    private fun syncChannels(list: List<String>) {
        if (auth.account == null) return
        val added = list - joinedChannels.toSet()
        val removed = joinedChannels - list.toSet()
        joinedChannels = list
        removed.forEach { ch ->
            irc.part(ch)
            buffers.close(ch)
            chatterRegistry.remove(ch)
            rooms.forget(ch)
            loadedChannels.remove(ch)
            _unreadMentions.update { it - ch }
        }
        added.forEach { ch ->
            irc.join(ch)
            buffers.open(ch)
            if (loadedChannels.add(ch)) scope.launch { loadChannelData(ch) }
        }
    }

    /** Emotes and badges first, then history, so old messages already render with emotes. */
    private suspend fun loadChannelData(channel: String) {
        val id = rooms.id(channel)
            ?: channelRepo.info.value[channel]?.id
            ?: channelRepo.refreshUsers(listOf(channel))[channel]
        if (id != null) {
            rooms.setId(channel, id)
            loadEmotesAndBadges(id)
            loadChatters(channel, id)
        }
        loadHistory(channel)
    }

    private suspend fun loadEmotesAndBadges(channelId: String) = coroutineScope {
        launch { emotes.loadChannel(channelId, auth.account?.userId) }
        launch { badges.loadChannel(channelId) }
    }

    /**
     * The chatter list Twitch keeps for the channel. It only answers where the user is moderator
     * or broadcaster, so a failure here is the normal case and stays quiet.
     */
    private suspend fun loadChatters(channel: String, channelId: String) {
        val userId = auth.account?.userId ?: return
        val users = try {
            helix.chatters(channelId, userId)
        } catch (e: Exception) {
            Log.d(TAG, "No chatter list for $channel: ${e.message}")
            return
        }
        withContext(worker) {
            chatterRegistry.setPresent(channel, users.associate { it.userLogin.lowercase() to it.userName.ifEmpty { it.userLogin } })
        }
    }

    /**
     * Loads recent messages from the recent-messages service and merges them into the buffer by
     * time. With [since], only messages newer than that timestamp are added (gap after reconnect).
     */
    private suspend fun loadHistory(channel: String, since: Long? = null) {
        if (!settings.value.loadHistory) return
        val lines = try {
            thirdParty.recentMessages(channel, 100).messages
        } catch (e: Exception) {
            Log.w(TAG, "History for $channel failed: ${e.message}")
            return
        }
        withContext(worker) {
            val self = auth.account?.login.orEmpty()
            val (mentions, muted, rules) = filters
            val items = lines.mapNotNull { line ->
                val msg = IrcMessage.parse(line) ?: return@mapNotNull null
                if (msg.command != "PRIVMSG" && msg.command != "USERNOTICE") return@mapNotNull null
                builder.build(msg, self, rooms.id(channel), mentions, historical = true)
                    ?.takeIf { (since == null || it.timestamp > since) && !muted.mutes(it) }
                    ?.let { rules.apply(it) }
                    ?.also { incoming.rememberChatter(channel, it) }
            }
            buffers.merge(channel, items)
        }
    }

    /** A mention, once [IncomingMessages] has built it: the inbox, the badge and the ringing. */
    private fun onMention(item: ChatItem, watched: Boolean) {
        // The inbox keeps every mention; one the user saw arrive is simply already read.
        _allMentions.tryEmit(MentionEvent(item, seen = watched))
        if (watched) return
        _unreadMentions.update { it + (item.channel to (it[item.channel] ?: 0) + 1) }
        // A muted channel still counts its mentions, it just does not notify about them.
        if (item.channel in channelRepo.mutedChannels.value) return
        _mentionEvents.tryEmit(item)
    }

    private fun onWhisper(whisper: InboxWhisper, watched: Boolean) {
        // One that arrived under the user's eyes is read already, and needs no notification.
        _whispers.tryEmit(whisper.copy(read = watched))
        if (!watched) _whisperEvents.tryEmit(whisper)
    }

    private fun system(channel: String, text: String) = buffers.add(
        ChatItem(id = UUID.randomUUID().toString(), channel = channel, kind = MessageKind.Notice,
            timestamp = System.currentTimeMillis(), systemText = text, text = text)
    )

    /**
     * Says which emotes are missing after a provider did not answer, so words that suddenly stay
     * plain text are not a mystery. A global failure hits every channel, a channel one only its own.
     */
    private fun reportEmoteFailure(failure: EmoteLoadFailure) {
        val providers = failure.providers.joinToString(", ") { it.label }
        if (failure.channelId == null) {
            val text = context.getString(R.string.chat_emotes_failed_global, providers)
            buffers.channels().forEach { system(it, text) }
        } else {
            val channel = rooms.channelOf(failure.channelId) ?: return
            system(channel, context.getString(R.string.chat_emotes_failed, providers))
        }
    }

    private fun systemAll(res: Int) {
        val text = context.getString(res)
        buffers.channels().forEach { system(it, text) }
    }

    private companion object {
        const val TAG = "ChatRepository"
        const val DUPLICATE_BYPASS = " \uDB40\uDC00"
        const val CTCP_ACTION = "\u0001ACTION "
        const val CTCP_END = "\u0001"
    }
}
