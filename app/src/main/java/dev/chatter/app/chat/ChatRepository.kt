package dev.chatter.app.chat

import android.content.Context
import android.util.Log
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.auth.TwitchScopes
import dev.chatter.app.badges.BadgeRepository
import dev.chatter.app.channels.BlockedUsersRepository
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.emotes.EmoteRepository
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A mention that just arrived, and whether its channel was open. */
data class MentionEvent(val item: ChatItem, val seen: Boolean)

enum class SendResult {
    Ok, Empty, NotConnected, RateLimited,
    /** A command was not executed (unknown or wrong usage); the hint is shown in the chat. */
    CommandError,
}

/**
 * Owns the chat of all channels. Every mutation runs on the single-threaded [worker], so no locks
 * are needed. Messages live in [MessageBuffers]; this class handles what Twitch sends.
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

    /** The messages and how they reach the screen. */
    private val buffers = MessageBuffers(scope, worker, settings)

    /** Channel state and the user's role per channel; see [Rooms]. */
    val rooms = Rooms()

    /** Windows on screen and what they show; see [ChatWindows]. */
    val windows = ChatWindows()

    /** Shared Chat sessions; see [SharedChats]. */
    val sharedChats = SharedChats()

    // --- state touched only on `worker` ---
    private val lastSent = HashMap<String, Pair<String, Long>>()
    private val rateLimiter = RateLimiter(30_000)
    private val loadedChannels = HashSet<String>()
    private var joinedChannels: List<String> = emptyList()

    /** The settings as applied to arriving messages. Rule changes affect new messages only. */
    @Volatile private var filters = ChatFilters()

    /** Handles what Twitch sends; see [IncomingMessages]. */
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
        sharedChats = sharedChats,
        onSharedChatStarted = { channel -> scope.launch { loadSharedChat(channel) } },
        onPartnersFound = { ids -> scope.launch { describePartners(ids) } },
        onRefused = { _refused.tryEmit(it) },
    )

    private val _refused = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Channels where Twitch just refused one of the user's messages, one event per refusal. */
    val refused: SharedFlow<String> = _refused

    private val _mentionEvents = MutableSharedFlow<ChatItem>(extraBufferCapacity = 32)
    /** Live mentions in channels the user is not looking at. */
    val mentionEvents: SharedFlow<ChatItem> = _mentionEvents

    private val _allMentions = MutableSharedFlow<MentionEvent>(extraBufferCapacity = 32)
    /** Every live mention, including those in the open channel. For the inbox. */
    val allMentions: SharedFlow<MentionEvent> = _allMentions

    private val _whispers = MutableSharedFlow<InboxWhisper>(extraBufferCapacity = 16)
    /**
     * Whispers. Twitch only delivers them to tokens with `whispers:read`, which older logins lack.
     */
    val whispers: SharedFlow<InboxWhisper> = _whispers

    private val _whisperEvents = MutableSharedFlow<InboxWhisper>(extraBufferCapacity = 16)
    /** Whispers that arrived while the whisper tab was not open, for the notification. */
    val whisperEvents: SharedFlow<InboxWhisper> = _whisperEvents

    private val _unreadMentions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMentions: StateFlow<Map<String, Int>> = _unreadMentions

    /** New messages per channel since the user last looked. */
    val unreadMessages: StateFlow<Map<String, Int>> get() = buffers.unreadMessages

    /**
     * The chat screen's page: a channel or a combined chat key. A bubble reads its own channel and
     * does not touch this.
     */
    val activePage = MutableStateFlow<String?>(null)

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
                    // Deleted messages are filtered when publishing, so showing them again needs a
                    // republish.
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
            channelRepo.groups.collect { groups -> buffers.setGroups(groups.mapValues { it.value.channels }) }
        }

        // A provider came back: messages on screen were built without its emotes and are rebuilt.
        scope.launch(worker) {
            emotes.recovered.collect { buffers.rebuildAll() }
        }

        scope.launch(worker) {
            var wasConnected = false
            var previous: ConnectionState? = null
            irc.state.collect { state ->
                when (state) {
                    ConnectionState.Connected -> if (wasConnected) {
                        systemAll(R.string.chat_reconnected)
                        // Fetch what was said while offline.
                        buffers.channels().forEach { ch -> scope.launch { loadHistory(ch, since = incoming.lastLive(ch)) } }
                    } else wasConnected = true
                    // Said once when the connection drops, not for every retry.
                    ConnectionState.Connecting, ConnectionState.WaitingForNetwork ->
                        if (previous == ConnectionState.Connected) systemAll(R.string.chat_disconnected)
                    else -> Unit
                }
                previous = state
            }
        }
    }

    /** The messages of a channel, or of a combined chat by its key. */
    fun messages(page: String): StateFlow<List<ChatItem>> = buffers.messages(page)

    /** Adds an info line (e.g. 7TV activity), optionally followed by emote and text segments. */
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

    /** The latest messages of one user in a channel, oldest first, for the user card. */
    suspend fun messagesFrom(channel: String, login: String, limit: Int = 30): List<ChatItem> =
        buffers.from(channel, login, limit)

    /** Display names of recently active chatters, most recent first. */
    suspend fun chatters(channel: String): List<String> = withContext(worker) {
        chatterRegistry.names(channel)
    }

    suspend fun send(channel: String, input: String, replyTo: ChatItem?): SendResult = withContext(worker) {
        var text = input.trim()
        if (text.isEmpty()) return@withContext SendResult.Empty
        // A guest connection is read-only, and every command needs a login.
        if (auth.account == null) return@withContext SendResult.NotConnected
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
            // Twitch files the answer under the parent's conversation; the echo does the same.
            ReplyInfo(it.id, it.login.orEmpty(), it.displayName.orEmpty(), it.text, threadId = it.reply?.threadId ?: it.id)
        }
        incoming.sent(
            builder.buildOwn(channel, wire, state, auth.account?.login.orEmpty(), rooms.id(channel), reply, replyParent?.segments.orEmpty()),
        )
        stats.countSent(channel)
        SendResult.Ok
    }

    /**
     * Runs a moderation command (e.g. from the message actions) and reports the result in the chat.
     */
    fun runCommand(channel: String, command: ChatCommand) = scope.launch(worker) {
        system(channel, commands.execute(command, rooms.id(channel)))
    }

    /** Drops all buffers, e.g. after logout. */
    fun reset() = scope.launch(worker) {
        buffers.clearAll()
        chatterRegistry.clear()
        rooms.clear()
        incoming.clear()
        sharedChats.clear()
        loadedChannels.clear()
        joinedChannels = emptyList()
        _unreadMentions.value = emptyMap()
    }

    /** Joins all channels again. Called after login. */
    fun resync() = scope.launch(worker) {
        joinedChannels = emptyList()
        syncChannels(channelRepo.channels.value)
    }

    // ---------------------------------------------------------------------------------------

    private fun syncChannels(list: List<String>) {
        if (!auth.canRead) return
        val added = list - joinedChannels.toSet()
        val removed = joinedChannels - list.toSet()
        joinedChannels = list
        removed.forEach { ch ->
            irc.part(ch)
            buffers.close(ch)
            chatterRegistry.remove(ch)
            rooms.forget(ch)
            sharedChats.forget(ch)
            loadedChannels.remove(ch)
            _unreadMentions.update { it - ch }
        }
        added.forEach { ch ->
            irc.join(ch)
            buffers.open(ch)
            if (loadedChannels.add(ch)) scope.launch { loadChannelData(ch) }
        }
    }

    /**
     * Emotes and badges first, then history, so old messages render with emotes. But only for a
     * moment: an unreachable provider does not refuse, it times out, which once meant twenty
     * seconds of empty channel. Emotes that arrive later are put into the messages afterwards.
     */
    private suspend fun loadChannelData(channel: String) {
        // First, so the user does not look at an empty screen while the channel id and emotes load.
        announceHistory(channel)
        val id = rooms.id(channel)
            ?: channelRepo.info.value[channel]?.id
            ?: channelRepo.refreshUsers(listOf(channel))[channel]
        if (id != null) {
            rooms.setId(channel, id)
            val emoteLoad = scope.launch { loadEmotesAndBadges(id) }
            loadChatters(channel, id)
            withTimeoutOrNull(EMOTES_BEFORE_HISTORY_MS) { emoteLoad.join() }
        }
        loadHistory(channel)
    }

    private suspend fun loadEmotesAndBadges(channelId: String) = coroutineScope {
        launch { emotes.loadChannel(channelId, auth.account?.userId) }
        launch { badges.loadChannel(channelId) }
    }

    /**
     * Twitch's chatter list. It only answers where the user moderates, so failures are normal and
     * not reported.
     */
    private suspend fun loadChatters(channel: String, channelId: String) {
        val account = auth.account ?: return
        // Without the scope every join would just get a 401.
        if (!TwitchScopes.allows(account.scopes, "moderator:read:chatters")) return
        val userId = account.userId
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
     * Loads recent messages from the recent-messages service and merges them by time. With [since],
     * only messages after it (the gap after a reconnect).
     *
     * The service is often unavailable, and an empty channel looks broken, so the line
     * [announceHistory] put up is removed here or replaced by one saying the history failed.
     */
    private suspend fun loadHistory(channel: String, since: Long? = null) {
        if (!settings.value.loadHistory) return
        val announce = since == null
        val lines = try {
            thirdParty.recentMessages(channel, 100).messages
        } catch (e: Exception) {
            Log.w(TAG, "History for $channel failed: ${e.message}")
            if (announce) withContext(worker) {
                buffers.remove(channel, historyId(channel))
                system(channel, context.getString(R.string.chat_history_failed), id = historyFailedId(channel))
            }
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
            if (announce) buffers.remove(channel, historyId(channel))
            // Old partner messages are marked like new ones. Whether a session is running is
            // decided by live messages only.
            sharedChats.unknown(items.mapNotNullTo(HashSet()) { it.sourceRoomId })
        }.let { describePartners(it) }
    }

    /** Asks Helix who a channel that just started sharing its chat shares it with. */
    private suspend fun loadSharedChat(channel: String) {
        val roomId = rooms.id(channel) ?: return
        val session = try {
            helix.sharedChatSession(roomId)
        } catch (e: Exception) {
            // Partners still show up one by one as their messages arrive.
            Log.d(TAG, "No Shared Chat session for $channel: ${e.message}")
            return
        }
        val ids = session?.participants?.map { it.broadcasterId }.orEmpty()
        val unknown = withContext(worker) {
            sharedChats.setParticipants(channel, roomId, ids)
            sharedChats.unknown(ids - roomId)
        }
        describePartners(unknown)
    }

    /**
     * Name, picture and badges of Shared Chat partners. Their messages are drawn with the partner's
     * badges, since `source-badges` refers to the channel they were written in.
     */
    private suspend fun describePartners(ids: List<String>) {
        if (ids.isEmpty()) return
        val known = rooms.knownIds().toSet()
        ids.filter { it !in known }.forEach { scope.launch { badges.loadChannel(it) } }
        val users = try {
            helix.usersById(ids)
        } catch (e: Exception) {
            Log.w(TAG, "Shared Chat partners failed: ${e.message}")
            withContext(worker) { sharedChats.failed(ids) }
            return
        }
        val partners = users.map { ChatPartner(it.id, it.login, it.displayName, it.profileImageUrl.ifEmpty { null }) }
        withContext(worker) { sharedChats.described(partners) }
    }

    /** A built mention: inbox, unread count and notification. */
    private fun onMention(item: ChatItem, watched: Boolean) {
        // The inbox gets every mention; one seen arriving is already read.
        _allMentions.tryEmit(MentionEvent(item, seen = watched))
        if (watched) return
        _unreadMentions.update { it + (item.channel to (it[item.channel] ?: 0) + 1) }
        // Muted channels still count mentions, without notifying.
        if (item.channel in channelRepo.mutedChannels.value) return
        _mentionEvents.tryEmit(item)
    }

    private fun onWhisper(whisper: InboxWhisper, watched: Boolean) {
        // Read already if the whisper tab was open, and then no notification.
        _whispers.tryEmit(whisper.copy(read = watched))
        if (!watched) _whisperEvents.tryEmit(whisper)
    }

    /** [id] is set for lines that are removed again later, like the history status lines. */
    private fun system(channel: String, text: String, id: String = UUID.randomUUID().toString()) = buffers.add(
        ChatItem(id = id, channel = channel, kind = MessageKind.Notice,
            timestamp = System.currentTimeMillis(), systemText = text, text = text)
    )

    /**
     * Shows that the history is loading and removes an older failure line. Only for the initial
     * load; the gap fill after a reconnect is silent.
     */
    private suspend fun announceHistory(channel: String) {
        if (!settings.value.loadHistory) return
        withContext(worker) {
            buffers.remove(channel, historyFailedId(channel))
            system(channel, context.getString(R.string.chat_history_loading), id = historyId(channel))
        }
    }

    private fun historyId(channel: String) = "history-" + channel
    private fun historyFailedId(channel: String) = "history-failed-" + channel

    private fun systemAll(res: Int) {
        val text = context.getString(res)
        buffers.channels().forEach { system(it, text) }
    }

    private companion object {
        const val TAG = "ChatRepository"

        /** How long the history waits for the channel's emotes. */
        const val EMOTES_BEFORE_HISTORY_MS = 3_000L

        const val DUPLICATE_BYPASS = " \uDB40\uDC00"
        const val CTCP_ACTION = "\u0001ACTION "
        const val CTCP_END = "\u0001"
    }
}
