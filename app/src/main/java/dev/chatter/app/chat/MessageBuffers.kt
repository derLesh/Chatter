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
 * A combined chat has no buffer of its own. It is a list like a channel's, published the same way
 * and under its own key, but put together from its channels' buffers whenever one of them changes
 * — see [setGroups].
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

    /** The channels of each combined chat, by the key it is published under. */
    private var groups: Map<String, List<String>> = emptyMap()
    /** The other way round: the combined chats a channel is in, which a change to it concerns too. */
    private var groupsOf: Map<String, List<String>> = emptyMap()
    /** The rows each combined chat showed last time, by message id; see [snapshotGroup]. */
    private val groupRows = HashMap<String, HashMap<String, GroupRow>>()

    // New messages per channel since the user last looked at it. Counted here and published
    // together with the message lists, rather than on every single message.
    private val unreadCounts = HashMap<String, Int>()
    private var unreadDirty = false
    private val _unreadMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMessages: StateFlow<Map<String, Int>> = _unreadMessages

    /** The list one channel — or one combined chat, by its key — is drawn from. */
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

    /**
     * Which combined chats there are and which channels each one reads from. A combined chat whose
     * channels changed is put together again; one that is gone is let go of.
     */
    fun setGroups(next: Map<String, List<String>>) {
        (groups.keys - next.keys).forEach { key ->
            flows.remove(key)
            watchers.remove(key)?.cancel()
            dirty.remove(key)
            groupRows.remove(key)
        }
        val changed = next.filter { (key, channels) -> groups[key] != channels }.keys
        groups = next
        groupsOf = next.flatMap { (key, channels) -> channels.map { it to key } }
            .groupBy({ it.first }, { it.second })
        changed.forEach { markDirty(it) }
    }

    /** Drops everything, e.g. after logout. */
    fun clearAll() {
        buffers.clear()
        ids.clear()
        dirty.clear()
        groupRows.clear()
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

    /** Takes one message out again, by the id it went in under. */
    fun remove(channel: String, id: String) {
        val buffer = buffers[channel] ?: return
        if (ids[channel]?.remove(id) != true) return
        buffer.removeAll { it.id == id }
        markDirty(channel)
    }

    /**
     * Gives a message the id Twitch knows it by, in place of the one it went in under. Only the
     * user's own messages need this: they are shown before Twitch has named them.
     */
    fun rename(channel: String, from: String, to: String) {
        val buffer = buffers[channel] ?: return
        val known = ids[channel] ?: return
        val index = buffer.indexOfFirst { it.id == from }
        if (index < 0) return
        known.remove(from)
        // Already there under its real id, e.g. from a history that was quicker: one is enough.
        if (!known.add(to)) buffer.removeAt(index) else buffer[index] = buffer[index].copy(id = to)
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
        groups[channel]?.let { return snapshotGroup(channel, it) }
        val buffer = buffers[channel] ?: return emptyList()
        val out = if (settings.value.showDeleted) buffer.toList()
        else buffer.filterTo(ArrayList(buffer.size)) { !it.deleted }
        // Emotes and badges are worked out here rather than while drawing: the tables that takes
        // reading are this worker's, and the main thread must not touch them. Everything but the
        // messages that arrived since the last publish is built already, so this costs nothing.
        out.forEach { it.body.prepare() }
        return out
    }

    /**
     * The messages of several channels as one list, in the order they were written.
     *
     * Each channel's own order is left as it is — merging only ever decides which channel comes
     * next — so a line the app put somewhere on purpose stays there. The list is held to the same
     * limit a channel is: a combined chat of five would otherwise be five channels long to draw.
     *
     * Every other row is shaded by the combined chat itself. Each channel counts its own rows,
     * and mixed together those would come out in clumps. A row keeps the shade it was given for
     * as long as it is on screen, just as a channel's does, so nothing flickers when the list
     * moves on; and it keeps the copy it was given, so an unchanged message is the same object.
     */
    private fun snapshotGroup(key: String, channels: List<String>): List<ChatItem> {
        val lists = channels.mapNotNull { buffers[it] }
        val showDeleted = settings.value.showDeleted
        val merged = ArrayList<ChatItem>(lists.sumOf { it.size })
        val at = IntArray(lists.size)
        while (true) {
            var next = -1
            for (i in lists.indices) {
                if (at[i] == lists[i].size) continue
                if (next < 0 || lists[i][at[i]].timestamp < lists[next][at[next]].timestamp) next = i
            }
            if (next < 0) break
            val item = lists[next][at[next]++]
            if (showDeleted || !item.deleted) merged.add(item)
        }

        // The list keys its rows by id. Twitch's ids are unique across channels, but one message
        // shown in two of them (a shared chat) would be the same row twice.
        val seen = HashSet<String>(merged.size)
        val unique = merged.asReversed().filter { seen.add(it.id) }.take(settings.value.messageLimit).asReversed()

        val before = groupRows[key].orEmpty()
        val rows = HashMap<String, GroupRow>(unique.size)
        var alternate = true
        val out = unique.map { item ->
            val old = before[item.id]
            alternate = old?.shown?.alternate ?: !alternate
            val shown = when {
                item.alternate == alternate -> item
                old?.source === item -> old.shown
                else -> item.copy(alternate = alternate)
            }
            rows[item.id] = GroupRow(item, shown)
            shown
        }
        groupRows[key] = rows
        // Built here for the same reason as a channel's — see [snapshot].
        out.forEach { it.body.prepare() }
        return out
    }

    /** A message as a combined chat shows it, and the message in its channel it was made from. */
    private class GroupRow(val source: ChatItem, val shown: ChatItem)

    private fun markDirty(channel: String) {
        dirty.add(channel)
        groupsOf[channel]?.let { dirty.addAll(it) }
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
