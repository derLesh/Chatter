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
import dev.chatter.app.irc.IrcConnection
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.ThirdPartyApi
import dev.chatter.app.settings.Settings
import dev.chatter.app.stats.StatsRepository
import dev.chatter.app.util.RateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * The UI observes one [StateFlow] per channel; updates are coalesced (see [PUBLISH_INTERVAL_MS])
 * and skipped entirely while nobody is watching (app in background).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(
    private val context: Context,
    private val irc: IrcConnection,
    private val builder: MessageBuilder,
    private val emotes: EmoteRepository,
    private val badges: BadgeRepository,
    private val channelRepo: ChannelRepository,
    private val thirdParty: ThirdPartyApi,
    private val helix: HelixApi,
    private val auth: AuthRepository,
    private val commands: CommandExecutor,
    private val chatterRegistry: ChatterRegistry,
    private val blocked: BlockedUsersRepository,
    private val stats: StatsRepository,
    private val rules: StateFlow<List<ChatRule>>,
    private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
) {
    private val worker = Dispatchers.Default.limitedParallelism(1)

    // --- state touched only on `worker` ---
    private val buffers = HashMap<String, ArrayDeque<ChatItem>>()
    /** Ids per channel buffer; the UI list uses ids as keys, which must be unique. */
    private val bufferIds = HashMap<String, HashSet<String>>()
    private val dirty = HashSet<String>()
    private var publishJob: Job? = null
    private val userStates = HashMap<String, Map<String, String>>()
    private var globalUserState: Map<String, String> = emptyMap()
    private val lastSent = HashMap<String, Pair<String, Long>>()
    private val rateLimiter = RateLimiter(30_000)
    private val loadedChannels = HashSet<String>()
    /** Server timestamp of the newest live message per channel, to fill gaps after a reconnect. */
    private val lastLiveTimestamp = HashMap<String, Long>()
    private var joinedChannels: List<String> = emptyList()
    private var mentions = MentionMatcher("", emptyList())
    private var muted = MuteFilter()
    /**
     * The user's highlight rules. They are applied as messages arrive, so changing a rule paints
     * what comes next — the messages already on screen stay as they were read.
     */
    private var ruleEngine = RuleEngine()

    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<ChatItem>>>()
    private val flowWatchers = ConcurrentHashMap<String, Job>()
    private val roomIds = ConcurrentHashMap<String, String>()

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

    private val _modChannels = MutableStateFlow<Set<String>>(emptySet())
    /** Channels where the user is moderator or broadcaster. */
    val modChannels: StateFlow<Set<String>> = _modChannels

    private val _roomStates = MutableStateFlow<Map<String, RoomState>>(emptyMap())
    /** Active chat modes (slow, followers-only, ...) per channel. */
    val roomStates: StateFlow<Map<String, RoomState>> = _roomStates

    private val _readyChannels = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Channels Twitch has confirmed the join for. Its ROOMSTATE is the answer to a JOIN, so it is
     * the first moment a message sent to that channel is actually accepted.
     */
    val readyChannels: StateFlow<Set<String>> = _readyChannels

    private val _roles = MutableStateFlow<Map<String, ChatRole>>(emptyMap())
    /** The user's role (VIP, moderator, broadcaster) per channel. */
    val roles: StateFlow<Map<String, ChatRole>> = _roles

    private val _unreadMentions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMentions: StateFlow<Map<String, Int>> = _unreadMentions

    // New messages per channel since the user last looked at it. Counted on `worker`,
    // published together with the message lists (not on every single message).
    private val unreadCounts = HashMap<String, Int>()
    private var unreadCountsDirty = false
    private val _unreadMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMessages: StateFlow<Map<String, Int>> = _unreadMessages

    /** The channel currently shown on screen, and whether the app UI is visible at all. */
    val activeChannel = MutableStateFlow<String?>(null)
    val uiVisible = MutableStateFlow(false)

    /** True while the whisper tab of the inbox is the thing in front of the user. */
    val whispersVisible = MutableStateFlow(false)

    fun start() {
        scope.launch(worker) { irc.messages.collect { handle(it) } }

        scope.launch(worker) {
            var showedDeleted = settings.value.showDeleted
            combine(auth.state, settings, blocked.logins) { _, s, blockedLogins -> s to blockedLogins }
                .collect { (s, blockedLogins) ->
                    mentions = MentionMatcher(auth.account?.login.orEmpty(), s.mentionKeywords)
                    muted = MuteFilter(s.muteKeywords, blockedLogins)
                    dropMuted()
                    trimAll(s.messageLimit)
                    // Deleted messages are filtered out when publishing, so turning them back on
                    // has to republish what is already buffered.
                    if (s.showDeleted != showedDeleted) {
                        showedDeleted = s.showDeleted
                        buffers.keys.forEach { markDirty(it) }
                    }
                }
        }

        scope.launch(worker) {
            rules.collect { ruleEngine = RuleEngine(it) }
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
                        buffers.keys.forEach { ch -> scope.launch { loadHistory(ch, since = lastLiveTimestamp[ch] ?: 0L) } }
                    } else wasConnected = true
                    ConnectionState.Connecting -> if (wasConnected) systemAll(R.string.chat_disconnected)
                    else -> Unit
                }
            }
        }

        scope.launch {
            activeChannel.collect { ch -> if (ch != null) clearUnread(ch) }
        }
    }

    fun messages(channel: String): StateFlow<List<ChatItem>> = flows.computeIfAbsent(channel) { ch ->
        MutableStateFlow<List<ChatItem>>(emptyList()).also { flow ->
            // When the UI starts watching again, push the latest buffer immediately.
            flowWatchers[ch] = scope.launch(worker) { flow.subscriptionCount.filter { it > 0 }.collect { markDirty(ch) } }
        }
    }

    fun roomId(channel: String): String? = roomIds[channel]

    fun channelForRoomId(id: String): String? = roomIds.entries.firstOrNull { it.value == id }?.key

    fun knownRoomIds(): List<String> = roomIds.values.toList()

    /** Adds an informational line (e.g. 7TV activity), optionally followed by emotes/text segments. */
    fun postNotice(channel: String, text: String, segments: List<Segment> = emptyList()) = scope.launch(worker) {
        append(
            ChatItem(id = UUID.randomUUID().toString(), channel = channel, kind = MessageKind.Notice,
                timestamp = System.currentTimeMillis(), systemText = text, text = text,
                body = MessageBody.of(segments))
        )
    }

    fun clearUnread(channel: String) {
        _unreadMentions.update { it - channel }
        scope.launch(worker) {
            if (unreadCounts.remove(channel) != null) _unreadMessages.value = HashMap(unreadCounts)
        }
    }

    /** The latest messages of one user in a channel (oldest first), for the user card. */
    suspend fun messagesFrom(channel: String, login: String, limit: Int = 30): List<ChatItem> = withContext(worker) {
        buffers[channel]?.filter { it.login.equals(login, ignoreCase = true) }?.takeLast(limit).orEmpty()
            // The card draws these, so they have to be built here — see `snapshot`.
            .onEach { it.body.prepare() }
    }

    /** Display names of recently active chatters, most recent first. */
    suspend fun chatters(channel: String): List<String> = withContext(worker) {
        chatterRegistry.names(channel)
    }

    suspend fun send(channel: String, input: String, replyTo: ChatItem?): SendResult = withContext(worker) {
        var text = input.trim()
        if (text.isEmpty()) return@withContext SendResult.Empty
        CommandParser.parse(text)?.let { command ->
            system(channel, commands.execute(command, roomIds[channel]))
            val invalid = command is ChatCommand.Usage || command is ChatCommand.Unknown
            return@withContext if (invalid) SendResult.CommandError else SendResult.Ok
        }

        val now = System.currentTimeMillis()
        // Twitch drops identical consecutive messages; an invisible tag character avoids that.
        lastSent[channel]?.let { (prev, at) -> if (prev == text && now - at < 30_000) text += DUPLICATE_BYPASS }

        val state = userStates[channel] ?: globalUserState
        val privileged = state["badges"].orEmpty().split(',').any {
            it.startsWith("moderator/") || it.startsWith("broadcaster/") || it.startsWith("vip/")
        }
        if (!rateLimiter.tryAcquire(now, if (privileged) 100 else 20)) return@withContext SendResult.RateLimited

        val wire = if (text.startsWith("/me ")) "$CTCP_ACTION${text.substring(4)}$CTCP_END" else text
        val replyParent = replyTo?.takeIf { it.canReply && !it.id.startsWith("local-") }
        if (!irc.sendMessage(channel, wire, replyParent?.id)) return@withContext SendResult.NotConnected

        lastSent[channel] = input.trim() to now
        val reply = replyParent?.let {
            ReplyInfo(it.id, it.login.orEmpty(), it.displayName.orEmpty(), it.text)
        }
        append(builder.buildOwn(channel, wire, state, auth.account?.login.orEmpty(), roomIds[channel], reply))
        stats.countSent(channel)
        SendResult.Ok
    }

    /** Runs a moderation command (e.g. from the message actions) and reports the result in the chat. */
    fun runCommand(channel: String, command: ChatCommand) = scope.launch(worker) {
        system(channel, commands.execute(command, roomIds[channel]))
    }

    /** Removes everything a newly blocked or muted chatter said from the buffers. */
    private fun dropMuted() {
        buffers.forEach { (channel, buffer) ->
            val ids = bufferIds[channel]
            val removed = buffer.removeAll { item ->
                muted.mutes(item).also { if (it) ids?.remove(item.id) }
            }
            if (removed) markDirty(channel)
        }
    }

    /** Drops all buffers, e.g. after logout. */
    fun reset() = scope.launch(worker) {
        buffers.clear()
        bufferIds.clear()
        chatterRegistry.clear()
        userStates.clear()
        loadedChannels.clear()
        lastLiveTimestamp.clear()
        _modChannels.value = emptySet()
        _roomStates.value = emptyMap()
        _roles.value = emptyMap()
        _readyChannels.value = emptySet()
        joinedChannels = emptyList()
        flows.values.forEach { it.value = emptyList() }
        _unreadMentions.value = emptyMap()
        unreadCounts.clear()
        _unreadMessages.value = emptyMap()
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
            buffers.remove(ch)
            bufferIds.remove(ch)
            chatterRegistry.remove(ch)
            loadedChannels.remove(ch)
            flows.remove(ch)
            _roomStates.update { it - ch }
            _roles.update { it - ch }
            flowWatchers.remove(ch)?.cancel()
            _readyChannels.update { it - ch }
            clearUnread(ch)
        }
        added.forEach { ch ->
            irc.join(ch)
            buffers.getOrPut(ch) { ArrayDeque() }
            if (loadedChannels.add(ch)) scope.launch { loadChannelData(ch) }
        }
    }

    /** Emotes and badges first, then history, so old messages already render with emotes. */
    private suspend fun loadChannelData(channel: String) {
        val id = roomIds[channel]
            ?: channelRepo.info.value[channel]?.id
            ?: channelRepo.refreshUsers(listOf(channel))[channel]
        if (id != null) {
            roomIds[channel] = id
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
            val buffer = buffers[channel] ?: return@withContext
            val self = auth.account?.login.orEmpty()
            val ids = bufferIds.getOrPut(channel) { HashSet() }
            val items = lines.mapNotNull { line ->
                val msg = IrcMessage.parse(line) ?: return@mapNotNull null
                if (msg.command != "PRIVMSG" && msg.command != "USERNOTICE") return@mapNotNull null
                builder.build(msg, self, roomIds[channel], mentions, historical = true)
                    ?.takeIf { (since == null || it.timestamp > since) && !muted.mutes(it) }
                    ?.let { ruleEngine.apply(it) }
                    ?.takeIf { ids.add(it.id) }
                    ?.also { rememberChatter(channel, it) }
            }
            if (items.isEmpty()) return@withContext
            // Stable sort: live messages and history end up in chronological order.
            val merged = ArrayList<ChatItem>(buffer.size + items.size).apply {
                addAll(buffer)
                addAll(items)
                sortBy { it.timestamp }
            }
            buffer.clear()
            // Re-number the alternating backgrounds (only happens on join / reconnect).
            merged.forEachIndexed { i, m -> buffer.addLast(if (m.alternate == (i % 2 == 1)) m else m.copy(alternate = i % 2 == 1)) }
            trim(channel, buffer)
            markDirty(channel)
        }
    }

    private fun handle(msg: IrcMessage) {
        val channel = msg.channel
        when (msg.command) {
            "PRIVMSG", "USERNOTICE" -> {
                if (channel == null) return
                val built = builder.build(msg, auth.account?.login.orEmpty(), roomIds[channel], mentions) ?: return
                lastLiveTimestamp[channel] = built.timestamp
                // Muted and hidden messages still count as "seen", so a reconnect does not fetch
                // them again.
                if (muted.mutes(built)) return
                val item = ruleEngine.apply(built) ?: return
                rememberChatter(channel, item)
                append(item)
                // Only live messages: the history fetched on join was received long ago.
                if (!item.isOwn) stats.countReceived()
                if (!item.isOwn && !(uiVisible.value && activeChannel.value == channel)) {
                    unreadCounts[channel] = (unreadCounts[channel] ?: 0) + 1
                    unreadCountsDirty = true
                }
                if (item.isMention) onMention(item)
            }
            "WHISPER" -> InboxWhisper.from(msg)?.let(::onWhisper)
            "NOTICE" -> if (channel != null) builder.build(msg, "", null, mentions)?.let(::append)
            "CLEARCHAT" -> if (channel != null) onClearChat(channel, msg)
            "CLEARMSG" -> if (channel != null) msg.tag("target-msg-id")?.let { id -> markDeleted(channel) { it.id == id } }
            "ROOMSTATE" -> if (channel != null) {
                _roomStates.update { it + (channel to (it[channel] ?: RoomState()).update(msg.tags)) }
                _readyChannels.update { it + channel }
                msg.tag("room-id")?.let { id ->
                    if (roomIds.put(channel, id) == null) scope.launch { loadEmotesAndBadges(id) }
                }
            }
            "USERSTATE" -> if (channel != null) {
                userStates[channel] = msg.tags
                val badges = msg.tag("badges").orEmpty()
                val role = ChatRole.fromBadges(badges)
                val isMod = role == ChatRole.Moderator || role == ChatRole.Broadcaster
                _modChannels.update { if (isMod) it + channel else it - channel }
                _roles.update { if (it[channel] == role) it else it + (channel to role) }
            }
            "GLOBALUSERSTATE" -> globalUserState = msg.tags
        }
    }

    private fun onMention(item: ChatItem) {
        stats.countMention()
        val watching = uiVisible.value && activeChannel.value == item.channel
        // The inbox keeps every mention; one the user saw arrive is simply already read.
        _allMentions.tryEmit(MentionEvent(item, seen = watching))
        if (watching) return
        _unreadMentions.update { it + (item.channel to (it[item.channel] ?: 0) + 1) }
        // A muted channel still counts its mentions, it just does not notify about them.
        if (item.channel in channelRepo.mutedChannels.value) return
        _mentionEvents.tryEmit(item)
    }

    private fun onWhisper(whisper: InboxWhisper) {
        if (muted.mutes(whisper.login, whisper.displayName, whisper.text)) return
        // One that arrived under the user's eyes is read already, and needs no notification.
        val watching = uiVisible.value && whispersVisible.value
        _whispers.tryEmit(whisper.copy(read = watching))
        if (!watching) _whisperEvents.tryEmit(whisper)
    }

    private fun onClearChat(channel: String, msg: IrcMessage) {
        val target = msg.trailing
        if (target == null) {
            system(channel, context.getString(R.string.chat_cleared))
            return
        }
        markDeleted(channel) { it.login == target }
        val duration = msg.tag("ban-duration")
        system(
            channel,
            if (duration != null) context.getString(R.string.chat_timeout, target, duration.toIntOrNull() ?: 0)
            else context.getString(R.string.chat_ban, target),
        )
    }

    private fun rememberChatter(channel: String, item: ChatItem) {
        val login = item.login ?: return
        chatterRegistry.remember(channel, login, item.displayName, item.color)
    }

    private fun system(channel: String, text: String) = append(
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
            buffers.keys.forEach { system(it, text) }
        } else {
            val channel = channelForRoomId(failure.channelId) ?: return
            system(channel, context.getString(R.string.chat_emotes_failed, providers))
        }
    }

    private fun systemAll(res: Int) {
        val text = context.getString(res)
        buffers.keys.forEach { system(it, text) }
    }

    private fun append(item: ChatItem) {
        val buffer = buffers[item.channel] ?: return
        if (!bufferIds.getOrPut(item.channel) { HashSet() }.add(item.id)) return
        buffer.addLast(item.copy(alternate = !(buffer.lastOrNull()?.alternate ?: true)))
        trim(item.channel, buffer)
        markDirty(item.channel)
    }

    private fun trim(channel: String, buffer: ArrayDeque<ChatItem>, limit: Int = settings.value.messageLimit) {
        val ids = bufferIds[channel]
        while (buffer.size > limit) ids?.remove(buffer.removeFirst().id)
    }

    private fun trimAll(limit: Int) {
        buffers.forEach { (ch, buf) ->
            if (buf.size > limit) {
                trim(ch, buf, limit)
                markDirty(ch)
            }
        }
    }

    private inline fun markDeleted(channel: String, predicate: (ChatItem) -> Boolean) {
        val buffer = buffers[channel] ?: return
        var changed = false
        for (i in buffer.indices) {
            val item = buffer[i]
            if (!item.deleted && predicate(item)) {
                buffer[i] = item.copy(deleted = true)
                changed = true
            }
        }
        if (changed) markDirty(channel)
    }

    /**
     * The list the UI gets. Deleted messages are dropped here rather than in the list itself:
     * the buffer has to be copied for publishing anyway, and filtering a second copy out of that
     * one meant two full lists per channel every 32 ms.
     */
    private fun snapshot(channel: String): List<ChatItem> {
        val buffer = buffers[channel] ?: return emptyList()
        val out = if (settings.value.showDeleted) buffer.toList()
        else buffer.filterTo(ArrayList(buffer.size)) { !it.deleted }
        // Emotes and badges are worked out here rather than while drawing: the tables that takes
        // reading are this worker's, and the main thread must not touch them. Everything but the
        // messages that arrived since the last publish is built already, so this costs nothing.
        out.forEach { it.body.prepare() }
        return out
    }

    private fun markDirty(channel: String) {
        dirty.add(channel)
        if (publishJob?.isActive == true) return
        // With the UI gone there is nothing for a publish to do, and a busy channel would
        // otherwise start a timer every 32 ms just to find that out. Subscribing marks the
        // channel dirty again (see `messages`), so nothing is lost by not scheduling now.
        if (flows.values.none { it.subscriptionCount.value > 0 }) return
        publishJob = scope.launch(worker) {
            delay(PUBLISH_INTERVAL_MS)
            val iterator = dirty.iterator()
            while (iterator.hasNext()) {
                val ch = iterator.next()
                val flow = flows[ch]
                // Nobody is looking: keep it dirty and publish once someone subscribes.
                if (flow == null || flow.subscriptionCount.value == 0) continue
                flow.value = snapshot(ch)
                iterator.remove()
            }
            if (unreadCountsDirty) {
                unreadCountsDirty = false
                _unreadMessages.value = HashMap(unreadCounts)
            }
        }
    }

    private companion object {
        const val TAG = "ChatRepository"
        /**
         * How often the message list a channel shows is replaced.
         *
         * Every publish copies the whole buffer and hands Compose a new list to tell apart, so a
         * busy channel pays for this a lot. At a frame a go it was thirty times a second, which
         * is thirty lists of up to five hundred messages — and a chat that moves faster than it
         * can be read gains nothing from it. Ten times a second still looks continuous.
         */
        const val PUBLISH_INTERVAL_MS = 100L
        const val DUPLICATE_BYPASS = " \uDB40\uDC00"
        const val CTCP_ACTION = "\u0001ACTION "
        const val CTCP_END = "\u0001"
    }
}
