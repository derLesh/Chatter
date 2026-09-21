package dev.chatter.app.chat

import dev.chatter.app.settings.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The messages of every joined channel, on their way to the screen.
 *
 * One buffer per channel, trimmed to the limit from the settings, and the ids in it beside them so
 * that the same message arriving twice — once from the history, once live — is only kept once.
 *
 * Everything here is touched on [worker], a single thread, so none of it needs a lock. The two
 * exceptions are [messages] and [unreadMessages], which the screen reads from wherever it likes.
 */
class MessageBuffers(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val settings: StateFlow<Settings>,
) {
    private val buffers = HashMap<String, ArrayDeque<ChatItem>>()
    /** The ids in each buffer; the UI list uses ids as keys, which must be unique. */
    private val ids = HashMap<String, HashSet<String>>()
    private val dirty = HashSet<String>()
    private var publishJob: Job? = null

    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<ChatItem>>>()
    private val watchers = ConcurrentHashMap<String, Job>()

    // New messages per channel since the user last looked at it. Counted here and published
    // together with the message lists, rather than on every single message.
    private val unreadCounts = HashMap<String, Int>()
    private var unreadDirty = false
    private val _unreadMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMessages: StateFlow<Map<String, Int>> = _unreadMessages

    /** The list one channel is drawn from. */
    fun messages(channel: String): StateFlow<List<ChatItem>> = flows.computeIfAbsent(channel) { ch ->
        MutableStateFlow<List<ChatItem>>(emptyList()).also { flow ->
            // When the UI starts watching again, push the latest buffer immediately.
            watchers[ch] = scope.launch(worker) { flow.subscriptionCount.filter { it > 0 }.collect { markDirty(ch) } }
        }
    }

    /** The channels that have a buffer right now. */
    fun channels(): List<String> = buffers.keys.toList()

    fun open(channel: String) {
        buffers.getOrPut(channel) { ArrayDeque() }
    }

    fun close(channel: String) {
        buffers.remove(channel)
        ids.remove(channel)
        dirty.remove(channel)
        flows.remove(channel)
        watchers.remove(channel)?.cancel()
        clearUnread(channel)
    }

    /** Drops everything, e.g. after logout. */
    fun clearAll() {
        buffers.clear()
        ids.clear()
        dirty.clear()
        flows.values.forEach { it.value = emptyList() }
        unreadCounts.clear()
        unreadDirty = false
        _unreadMessages.value = emptyMap()
    }

    /** Adds a message to its channel, unless the channel holds it already. */
    fun add(item: ChatItem) {
        val buffer = buffers[item.channel] ?: return
        if (!ids.getOrPut(item.channel) { HashSet() }.add(item.id)) return
        buffer.addLast(item.copy(alternate = !(buffer.lastOrNull()?.alternate ?: true)))
        trim(item.channel, buffer)
        markDirty(item.channel)
    }

    /**
     * Folds fetched history into a channel: whatever is not in it already, in the order it was
     * written rather than the order it arrived.
     */
    fun merge(channel: String, items: List<ChatItem>) {
        val buffer = buffers[channel] ?: return
        val known = ids.getOrPut(channel) { HashSet() }
        val fresh = items.filter { known.add(it.id) }
        if (fresh.isEmpty()) return
        // Stable sort: live messages and history end up in chronological order.
        val merged = ArrayList<ChatItem>(buffer.size + fresh.size).apply {
            addAll(buffer)
            addAll(fresh)
            sortBy { it.timestamp }
        }
        buffer.clear()
        // Re-number the alternating backgrounds (only happens on join / reconnect).
        merged.forEachIndexed { i, m -> buffer.addLast(if (m.alternate == (i % 2 == 1)) m else m.copy(alternate = i % 2 == 1)) }
        trim(channel, buffer)
        markDirty(channel)
    }

    /**
     * Works every message out again, for emotes that arrived after they were drawn.
     *
     * The bodies are replaced rather than emptied, because the list on screen is compared with
     * the one before it: a message whose body is the same object is the same message, and nothing
     * would be redrawn. What cannot be built again (a line the app wrote itself) stays as it is.
     */
    fun rebuildAll() {
        buffers.forEach { (channel, buffer) ->
            var changed = false
            for (i in buffer.indices) {
                val item = buffer[i]
                val body = item.body.rebuilt()
                if (body !== item.body) {
                    buffer[i] = item.copy(body = body)
                    changed = true
                }
            }
            if (changed) markDirty(channel)
        }
    }

    /** Strikes through whatever a moderator has taken back. */
    fun markDeleted(channel: String, predicate: (ChatItem) -> Boolean) {
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

    /** Throws out what the mute list covers now, e.g. after a word was added to it. */
    fun dropMuted(muted: MuteFilter) {
        buffers.forEach { (channel, buffer) ->
            val known = ids[channel]
            val removed = buffer.removeAll { item ->
                muted.mutes(item).also { if (it) known?.remove(item.id) }
            }
            if (removed) markDirty(channel)
        }
    }

    fun trimAll(limit: Int) {
        buffers.forEach { (channel, buffer) ->
            if (buffer.size > limit) {
                trim(channel, buffer, limit)
                markDirty(channel)
            }
        }
    }

    /** Hands every channel to the screen again, for a change in the way they are drawn. */
    fun republishAll() = buffers.keys.forEach { markDirty(it) }

    /** The latest messages of one user in a channel (oldest first), for the user card. */
    suspend fun from(channel: String, login: String, limit: Int): List<ChatItem> = withContext(worker) {
        buffers[channel]?.filter { it.login.equals(login, ignoreCase = true) }?.takeLast(limit).orEmpty()
            // The card draws these, so they have to be built here — see [snapshot].
            .onEach { it.body.prepare() }
    }

    fun countUnread(channel: String) {
        unreadCounts[channel] = (unreadCounts[channel] ?: 0) + 1
        unreadDirty = true
    }

    fun clearUnread(channel: String) {
        if (unreadCounts.remove(channel) != null) _unreadMessages.value = HashMap(unreadCounts)
    }

    private fun trim(channel: String, buffer: ArrayDeque<ChatItem>, limit: Int = settings.value.messageLimit) {
        val known = ids[channel]
        while (buffer.size > limit) known?.remove(buffer.removeFirst().id)
    }

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
        // otherwise start a timer every interval just to find that out. Subscribing marks the
        // channel dirty again (see [messages]), so nothing is lost by not scheduling now.
        if (flows.values.none { it.subscriptionCount.value > 0 }) return
        publishJob = scope.launch(worker) {
            delay(PUBLISH_INTERVAL_MS)
            val iterator = dirty.iterator()
            while (iterator.hasNext()) {
                val channel = iterator.next()
                val flow = flows[channel]
                // Nobody is looking: keep it dirty and publish once someone subscribes.
                if (flow == null || flow.subscriptionCount.value == 0) continue
                flow.value = snapshot(channel)
                iterator.remove()
            }
            if (unreadDirty) {
                unreadDirty = false
                _unreadMessages.value = HashMap(unreadCounts)
            }
        }
    }

    private companion object {
        /**
         * How often the message list a channel shows is replaced.
         *
         * Every publish copies the whole buffer and hands Compose a new list to tell apart, so a
         * busy channel pays for this a lot. At a frame a go it was thirty times a second, which
         * is thirty lists of up to five hundred messages — and a chat that moves faster than it
         * can be read gains nothing from it. Ten times a second still looks continuous.
         */
        const val PUBLISH_INTERVAL_MS = 100L
    }
}
