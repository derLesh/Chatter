package dev.chatter.app.chat

import android.content.Context
import android.util.Log
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.badges.BadgeRepository
import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.emotes.EmoteRepository
import dev.chatter.app.irc.ConnectionState
import dev.chatter.app.irc.IrcConnection
import dev.chatter.app.irc.IrcMessage
import dev.chatter.app.net.ThirdPartyApi
import dev.chatter.app.settings.Settings
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

enum class SendResult { Ok, Empty, NotConnected, RateLimited, UnsupportedCommand }

/**
 * Owns the message buffers of all channels.
 *
 * Every mutation runs on one single-threaded dispatcher ([worker]), so no locks are needed.
 * The UI observes one [StateFlow] per channel; updates are coalesced (at most every ~32 ms)
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
    private val auth: AuthRepository,
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
    private val chatters = HashMap<String, LinkedHashMap<String, String>>()
    private val lastSent = HashMap<String, Pair<String, Long>>()
    private val rateLimiter = RateLimiter(30_000)
    private val loadedChannels = HashSet<String>()
    private var joinedChannels: List<String> = emptyList()
    private var mentions = MentionMatcher("", emptyList())

    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<ChatItem>>>()
    private val flowWatchers = ConcurrentHashMap<String, Job>()
    private val roomIds = ConcurrentHashMap<String, String>()

    private val _mentionEvents = MutableSharedFlow<ChatItem>(extraBufferCapacity = 32)
    /** Emitted for live (non-history) messages that mention the user in a channel they are not looking at. */
    val mentionEvents: SharedFlow<ChatItem> = _mentionEvents

    private val _unreadMentions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMentions: StateFlow<Map<String, Int>> = _unreadMentions

    /** The channel currently shown on screen, and whether the app UI is visible at all. */
    val activeChannel = MutableStateFlow<String?>(null)
    val uiVisible = MutableStateFlow(false)

    fun start() {
        scope.launch(worker) { irc.messages.collect { handle(it) } }

        scope.launch(worker) {
            combine(auth.state, settings) { _, s -> s }.collect { s ->
                mentions = MentionMatcher(auth.account?.login.orEmpty(), s.mentionKeywords)
                trimAll(s.messageLimit)
            }
        }

        scope.launch(worker) {
            channelRepo.channels.collect { syncChannels(it) }
        }

        scope.launch(worker) {
            var wasConnected = false
            irc.state.collect { state ->
                when (state) {
                    ConnectionState.Connected -> if (wasConnected) systemAll(R.string.chat_reconnected) else wasConnected = true
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

    fun clearUnread(channel: String) = _unreadMentions.update { it - channel }

    /** Display names of recently active chatters, most recent first. */
    suspend fun chatters(channel: String): List<String> = withContext(worker) {
        chatters[channel]?.values?.reversed().orEmpty()
    }

    suspend fun send(channel: String, input: String, replyTo: ChatItem?): SendResult = withContext(worker) {
        var text = input.trim()
        if (text.isEmpty()) return@withContext SendResult.Empty
        if (text.startsWith("/") && !text.startsWith("/me ")) return@withContext SendResult.UnsupportedCommand

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
        SendResult.Ok
    }

    /** Drops all buffers, e.g. after logout. */
    fun reset() = scope.launch(worker) {
        buffers.clear()
        bufferIds.clear()
        chatters.clear()
        userStates.clear()
        loadedChannels.clear()
        joinedChannels = emptyList()
        flows.values.forEach { it.value = emptyList() }
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
            buffers.remove(ch)
            bufferIds.remove(ch)
            chatters.remove(ch)
            loadedChannels.remove(ch)
            flows.remove(ch)
            flowWatchers.remove(ch)?.cancel()
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
        }
        loadHistory(channel)
    }

    private suspend fun loadEmotesAndBadges(channelId: String) = coroutineScope {
        launch { emotes.loadChannel(channelId) }
        launch { badges.loadChannel(channelId) }
    }

    private suspend fun loadHistory(channel: String) {
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
                    ?.takeIf { ids.add(it.id) }
                    ?.also { rememberChatter(channel, it) }
            }
            items.asReversed().forEach { buffer.addFirst(it) }
            trim(channel, buffer)
            markDirty(channel)
        }
    }

    private fun handle(msg: IrcMessage) {
        val channel = msg.channel
        when (msg.command) {
            "PRIVMSG", "USERNOTICE" -> {
                if (channel == null) return
                val item = builder.build(msg, auth.account?.login.orEmpty(), roomIds[channel], mentions) ?: return
                rememberChatter(channel, item)
                append(item)
                if (item.isMention) onMention(item)
            }
            "NOTICE" -> if (channel != null) builder.build(msg, "", null, mentions)?.let(::append)
            "CLEARCHAT" -> if (channel != null) onClearChat(channel, msg)
            "CLEARMSG" -> if (channel != null) msg.tag("target-msg-id")?.let { id -> markDeleted(channel) { it.id == id } }
            "ROOMSTATE" -> if (channel != null) msg.tag("room-id")?.let { id ->
                if (roomIds.put(channel, id) == null) scope.launch { loadEmotesAndBadges(id) }
            }
            "USERSTATE" -> if (channel != null) userStates[channel] = msg.tags
            "GLOBALUSERSTATE" -> globalUserState = msg.tags
        }
    }

    private fun onMention(item: ChatItem) {
        if (uiVisible.value && activeChannel.value == item.channel) return
        _unreadMentions.update { it + (item.channel to (it[item.channel] ?: 0) + 1) }
        _mentionEvents.tryEmit(item)
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
        val map = chatters.getOrPut(channel) { LinkedHashMap(64, 0.75f, true) }
        map[login] = item.displayName ?: login
        if (map.size > MAX_CHATTERS) map.remove(map.keys.first())
    }

    private fun system(channel: String, text: String) = append(
        ChatItem(id = UUID.randomUUID().toString(), channel = channel, kind = MessageKind.Notice,
            timestamp = System.currentTimeMillis(), systemText = text, text = text)
    )

    private fun systemAll(res: Int) {
        val text = context.getString(res)
        buffers.keys.forEach { system(it, text) }
    }

    private fun append(item: ChatItem) {
        val buffer = buffers[item.channel] ?: return
        if (!bufferIds.getOrPut(item.channel) { HashSet() }.add(item.id)) return
        buffer.addLast(item)
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

    private fun markDirty(channel: String) {
        dirty.add(channel)
        if (publishJob?.isActive == true) return
        publishJob = scope.launch(worker) {
            delay(PUBLISH_INTERVAL_MS)
            val iterator = dirty.iterator()
            while (iterator.hasNext()) {
                val ch = iterator.next()
                val flow = flows[ch]
                // Nobody is looking: keep it dirty and publish once someone subscribes.
                if (flow == null || flow.subscriptionCount.value == 0) continue
                flow.value = buffers[ch]?.toList().orEmpty()
                iterator.remove()
            }
        }
    }

    private companion object {
        const val TAG = "ChatRepository"
        const val PUBLISH_INTERVAL_MS = 32L
        const val MAX_CHATTERS = 500
        const val DUPLICATE_BYPASS = " \uDB40\uDC00"
        const val CTCP_ACTION = "\u0001ACTION "
        const val CTCP_END = "\u0001"
    }
}
