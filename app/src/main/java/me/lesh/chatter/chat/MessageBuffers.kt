package me.lesh.chatter.chat

import me.lesh.chatter.badges.Badge
import me.lesh.chatter.badges.NamePaint
import me.lesh.chatter.settings.Settings
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
import kotlin.math.abs

/**
 * The messages of every joined channel on their way to the screen.
 *
 * One buffer per channel, trimmed to the configured limit, with the ids next to it so a message
 * that arrives twice (history and live) is kept once. A combined chat has no buffer; its list is
 * assembled from its channels' buffers (see [setGroups]).
 *
 * Everything runs on [worker], so no locks are needed, except [messages] and [unreadMessages],
 * which the UI reads from any thread.
 */
class MessageBuffers(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val settings: StateFlow<Settings>,
) {
    private val buffers = HashMap<String, ArrayDeque<ChatItem>>()
    /** The ids in each buffer; the UI uses them as keys, so they must be unique. */
    private val ids = HashMap<String, HashSet<String>>()
    private val dirty = HashSet<String>()
    private var publishJob: Job? = null
    private var rebuildJob: Job? = null
    private val rebuildChannels = HashSet<String>()
    private var rebuildGlobal = false

    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<ChatItem>>>()
    private val watchers = ConcurrentHashMap<String, Job>()
    private val preparationJobs = HashMap<String, Job>()

    /** The channels of each combined chat, by its key. */
    private var groups: Map<String, List<String>> = emptyMap()
    /** The combined chats each channel is part of. */
    private var groupsOf: Map<String, List<String>> = emptyMap()
    /** What each combined chat showed last; see [snapshotGroup]. */
    private val groupStates = HashMap<String, GroupState>()

    /**
     * Per channel: how many messages were appended and how often it changed otherwise, which a
     * combined chat compares with its last state to know whether it can just append; and the new
     * messages since the user last looked. Plain fields, since a map of boxed numbers would
     * allocate on every message.
     */
    private class Counts {
        var appended = 0L
        var reshaped = 0L
        var unread = 0
    }

    private val counts = HashMap<String, Counts>()

    private fun counts(channel: String) = counts.getOrPut(channel) { Counts() }

    private fun appended(channel: String) = counts[channel]?.appended ?: 0L
    private fun reshaped(channel: String) = counts[channel]?.reshaped ?: 0L

    // Unread counts are published together with the message lists instead of per message.
    private var unreadDirty = false
    private val _unreadMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadMessages: StateFlow<Map<String, Int>> = _unreadMessages

    private val _channelsWithNews = MutableStateFlow<Set<String>>(emptySet())
    /**
     * The channels with new messages, which only changes when one gets its first or is read. The
     * tabs need no more than this, and the counts change with every message.
     */
    val channelsWithNews: StateFlow<Set<String>> = _channelsWithNews

    /** The list a channel, or a combined chat by its key, is drawn from. */
    fun messages(channel: String): StateFlow<List<ChatItem>> = flows.computeIfAbsent(channel) { ch ->
        MutableStateFlow<List<ChatItem>>(emptyList()).also { flow ->
            // When the UI starts collecting again, push the current buffer right away.
            watchers[ch] = scope.launch(worker) { flow.subscriptionCount.filter { it > 0 }.collect { markDirty(ch) } }
        }
    }

    /** The channels that have a buffer. */
    fun channels(): List<String> = buffers.keys.toList()

    fun open(channel: String) {
        buffers.getOrPut(channel) { ArrayDeque() }
    }

    fun close(channel: String) {
        reshape(channel)
        buffers.remove(channel)
        ids.remove(channel)
        dirty.remove(channel)
        flows.remove(channel)
        watchers.remove(channel)?.cancel()
        preparationJobs.remove(channel)?.cancel()
        clearUnread(channel)
    }

    /**
     * Sets the combined chats and their channels. Changed ones are rebuilt, removed ones dropped.
     */
    fun setGroups(next: Map<String, List<String>>) {
        (groups.keys - next.keys).forEach { key ->
            flows.remove(key)
            watchers.remove(key)?.cancel()
            preparationJobs.remove(key)?.cancel()
            dirty.remove(key)
            groupStates.remove(key)
        }
        val changed = next.filter { (key, channels) -> groups[key] != channels }.keys
        groups = next
        groupsOf = next.flatMap { (key, channels) -> channels.map { it to key } }
            .groupBy({ it.first }, { it.second })
        changed.forEach { markDirty(it) }
    }

    /** Drops everything, e.g. after logout. */
    fun clearAll() {
        preparationJobs.values.forEach { it.cancel() }
        preparationJobs.clear()
        buffers.clear()
        ids.clear()
        dirty.clear()
        groupStates.clear()
        flows.values.forEach { it.value = emptyList() }
        counts.values.forEach { it.unread = 0 }
        unreadDirty = false
        publishUnread()
    }

    /** Adds a message to its channel unless it is already there. */
    fun add(item: ChatItem) {
        val buffer = buffers[item.channel] ?: return
        if (!ids.getOrPut(item.channel) { HashSet() }.add(item.id)) return
        val alternate = !(buffer.lastOrNull()?.alternate ?: true)
        buffer.addLast(if (item.alternate == alternate) item else item.copy(alternate = alternate))
        // Trimming only removes messages older than the oldest a combined chat with the same limit
        // still shows, so this stays an append.
        trim(item.channel, buffer)
        counts(item.channel).appended++
        markDirty(item.channel)
    }

    /** Merges fetched history into a channel: what is new, in the order it was written. */
    /** Returns the messages that were not in the channel yet. */
    fun merge(channel: String, items: List<ChatItem>): List<ChatItem> {
        val buffer = buffers[channel] ?: return emptyList()
        val known = ids.getOrPut(channel) { HashSet() }
        val fresh = items.filter { known.add(it.id) }
        if (fresh.isEmpty()) return fresh
        // Stable sort, so live messages and history end up in chronological order.
        val merged = ArrayList<ChatItem>(buffer.size + fresh.size).apply {
            addAll(buffer)
            addAll(fresh)
            sortBy { it.timestamp }
        }
        buffer.clear()
        // Renumber the alternating backgrounds (only on join and reconnect).
        merged.forEachIndexed { i, m -> buffer.addLast(if (m.alternate == (i % 2 == 1)) m else m.copy(alternate = i % 2 == 1)) }
        trim(channel, buffer)
        reshape(channel)
        return fresh
    }

    /**
     * Drops the user's unconfirmed local copies of [own] messages fetched from history: Twitch took
     * them after all, and only the answer naming their id was lost with the connection.
     */
    fun dropEchoesOf(channel: String, own: List<ChatItem>) {
        if (own.isEmpty()) return
        val buffer = buffers[channel] ?: return
        val known = ids[channel]
        val removed = buffer.removeAll { echo ->
            echo.isOwn && echo.id.startsWith("local-") &&
                own.any { sameWords(it.text, echo.text) && abs(it.timestamp - echo.timestamp) < ECHO_MATCH_MS }
                    .also { if (it) known?.remove(echo.id) }
        }
        if (removed) reshape(channel)
    }

    /** Equal but for spacing: a repeated line went out with an extra space; see [RepeatedMessages]. */
    private fun sameWords(a: String, b: String): Boolean {
        fun String.words() = filterNot { it.isWhitespace() || it == RepeatedMessages.SUFFIX.last() }
        return a.words() == b.words()
    }

    /** Removes one message by the id it was added under. */
    fun remove(channel: String, id: String) {
        val buffer = buffers[channel] ?: return
        if (ids[channel]?.remove(id) != true) return
        buffer.removeAll { it.id == id }
        reshape(channel)
    }

    /**
     * Gives a message the id Twitch knows it by. Only needed for the user's own messages, which are
     * shown before Twitch confirms them.
     */
    fun rename(channel: String, from: String, to: String) {
        val buffer = buffers[channel] ?: return
        val known = ids[channel] ?: return
        val index = buffer.indexOfFirst { it.id == from }
        if (index < 0) return
        known.remove(from)
        // Already there under its real id, e.g. from a faster history load.
        if (!known.add(to)) buffer.removeAt(index) else buffer[index] = buffer[index].copy(id = to)
        reshape(channel)
    }

    /** Coalesces nearby arrivals; null covers global tables, otherwise ids name Twitch channels. */
    fun rebuildAfterArrival(channelIds: Set<String>?) {
        if (channelIds == null) rebuildGlobal = true else rebuildChannels.addAll(channelIds)
        if (rebuildJob?.isActive == true) return
        rebuildJob = scope.launch(worker) {
            delay(PUBLISH_INTERVAL_MS)
            rebuildAll(if (rebuildGlobal) null else rebuildChannels.toSet())
            rebuildGlobal = false
            rebuildChannels.clear()
        }
    }

    /** Bodies are replaced so Compose redraws them; untouched channels keep their body objects. */
    fun rebuildAll(channelIds: Set<String>? = null) {
        buffers.forEach { (channel, buffer) ->
            var changed = false
            for (i in buffer.indices) {
                val item = buffer[i]
                val body = item.body.rebuilt(channelIds)
                if (body !== item.body) {
                    buffer[i] = item.copy(body = body)
                    changed = true
                }
            }
            if (changed) reshape(channel)
        }
    }

    /** Gives [login]'s messages the 7TV badge and paint they wear now. */
    fun restyle(login: String, badge: Badge?, paint: NamePaint?) {
        buffers.forEach { (channel, buffer) ->
            var changed = false
            for (i in buffer.indices) {
                val item = buffer[i]
                if (!login.equals(item.login, ignoreCase = true)) continue
                val body = item.body.withSevenTv(badge, paint)
                if (body !== item.body) {
                    buffer[i] = item.copy(body = body)
                    changed = true
                }
            }
            if (changed) reshape(channel)
        }
    }

    /** Strikes through messages a moderator deleted. */
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
        if (changed) reshape(channel)
    }

    /** A retained Shared Chat copy still needs badges from the channel it was written in. */
    fun usesBadgeChannel(channelId: String): Boolean =
        buffers.values.any { buffer -> buffer.any { it.sourceRoomId == channelId } }

    /** Drops what the mute list covers now, e.g. after a word was added. */
    fun dropMuted(muted: MuteFilter) {
        buffers.forEach { (channel, buffer) ->
            val known = ids[channel]
            val removed = buffer.removeAll { item ->
                muted.mutes(item).also { if (it) known?.remove(item.id) }
            }
            if (removed) reshape(channel)
        }
    }

    fun trimAll(limit: Int) {
        buffers.forEach { (channel, buffer) ->
            if (buffer.size > limit) {
                trim(channel, buffer, limit)
                reshape(channel)
            }
        }
    }

    /** Republishes every channel, for a change in how messages are drawn. */
    fun republishAll() = buffers.keys.forEach { markDirty(it) }

    /** The latest messages of one user in a channel, oldest first, for the user card. */
    suspend fun from(channel: String, login: String, limit: Int): List<ChatItem> = withContext(worker) {
        buffers[channel]?.filter { it.login.equals(login, ignoreCase = true) }?.takeLast(limit).orEmpty()
            // The card draws these, so they are built here; see [snapshot].
            .onEach { it.body.prepare() }
    }

    /** One message, built for drawing; null once it is no longer in the channel. */
    suspend fun find(channel: String, id: String): ChatItem? = withContext(worker) {
        buffers[channel]?.lastOrNull { it.id == id }?.also { it.body.prepare() }
    }

    suspend fun totalMessages(): Int = withContext(worker) { buffers.values.sumOf { it.size } }

    /** The newest [limit] messages of all channels together, oldest first. Not built for drawing. */
    suspend fun newest(limit: Int): List<ChatItem> = withContext(worker) {
        buffers.values.flatMap { it.takeLast(limit) }.sortedBy { it.timestamp }.takeLast(limit)
    }

    fun countUnread(channel: String) {
        counts(channel).unread++
        unreadDirty = true
    }

    fun clearUnread(channel: String) {
        val channelCounts = counts[channel] ?: return
        if (channelCounts.unread == 0) return
        channelCounts.unread = 0
        publishUnread()
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
        return out
    }

    /** Any change to [channel] other than appending; see [snapshotGroup]. */
    private fun reshape(channel: String) {
        counts(channel).reshaped++
        markDirty(channel)
    }

    /**
     * The messages of several channels as one list, in the order they were written.
     *
     * Each channel's own order is kept; merging only picks which channel comes next. The list has
     * the same limit as a channel, or a combined chat of five would be five times as long.
     *
     * The combined chat assigns the alternating backgrounds itself, since the channels' own
     * patterns would clump when mixed. A row keeps its shade and its object while on screen, so
     * nothing flickers and unchanged messages stay the same object.
     *
     * A full rebuild merges every channel and would run ten times a second in a busy combined chat.
     * When only new messages arrived after everything shown, they are appended ([appendToGroup]);
     * anything else (a deletion, merged history, a Shared Chat copy) rebuilds. The result is the
     * same either way.
     */
    private fun snapshotGroup(key: String, channels: List<String>): List<ChatItem> =
        groupStates[key]?.let { appendToGroup(it, channels) } ?: rebuildGroup(key, channels)

    /** The whole combined chat, assembled from its channels. */
    private fun rebuildGroup(key: String, channels: List<String>): List<ChatItem> {
        val lists = channels.mapNotNull { buffers[it] }
        val showDeleted = settings.value.showDeleted
        val all = mergeByTime(lists)
        val merged = all.filter { showDeleted || !it.deleted }

        // A Shared Chat message arrives in every channel of the session under different ids; only
        // the shared id links them. It is shown once, as the copy from the channel it was written
        // in if that channel is part of the combined chat.
        val native = merged.mapNotNullTo(HashSet()) { item -> item.sharedId.takeIf { item.sourceRoomId == null } }
        val seen = HashSet<String>(merged.size)
        val unique = merged.asReversed().filter { item ->
            val shared = item.sharedId
            when {
                shared == null -> seen.add(item.id)
                item.sourceRoomId != null && shared in native -> false
                else -> seen.add(shared)
            }
        }.take(settings.value.messageLimit).asReversed()

        val before = groupStates[key]?.byId.orEmpty()
        val state = GroupState(
            channels = channels,
            showDeleted = showDeleted,
            limit = settings.value.messageLimit,
            appended = channels.associateWith(::appended),
            reshaped = channels.associateWith(::reshaped),
        )
        state.latest = all.maxOfOrNull { it.timestamp }
        var alternate = true
        unique.forEach { item ->
            val old = before[item.id]
            alternate = old?.shown?.alternate ?: !alternate
            val shown = when {
                item.alternate == alternate -> item
                old?.source === item -> old.shown
                else -> item.copy(alternate = alternate)
            }
            state.add(GroupRow(item, shown))
        }
        groupStates[key] = state
        return state.publish()
    }

    /**
     * The combined chat plus what its channels appended since [state], or null if anything else
     * changed and it needs a rebuild. Checks everything before changing anything, so null leaves
     * [state] untouched.
     */
    private fun appendToGroup(state: GroupState, channels: List<String>): List<ChatItem>? {
        if (state.channels != channels || state.showDeleted != settings.value.showDeleted ||
            state.limit != settings.value.messageLimit
        ) return null
        val fresh = ArrayList<List<ChatItem>>(channels.size)
        for (channel in channels) {
            if (reshaped(channel) != state.reshaped[channel]) return null
            val count = appended(channel) - (state.appended[channel] ?: 0)
            if (count == 0L) continue
            val buffer = buffers[channel] ?: return null
            if (count > buffer.size) return null
            fresh += buffer.subList(buffer.size - count.toInt(), buffer.size)
        }
        if (fresh.isEmpty()) return state.published
        val all = mergeByTime(fresh)
        val added = all.filter { state.showDeleted || !it.deleted }

        // Only messages written after everything so far can be appended; earlier ones belong in the
        // middle. Ties go to the first channel, which may not be the append position, so they
        // rebuild.
        val latest = state.latest
        if (latest != null && all.minOf { it.timestamp } <= latest) return null
        val sharedAdded = HashSet<String>()
        val idsAdded = HashSet<String>()
        for (item in added) {
            val shared = item.sharedId
            // A Shared Chat copy decides which copy is shown and where.
            if (shared != null && (shared in state.shared || !sharedAdded.add(shared))) return null
            if (shared == null && (item.id in state.byId || !idsAdded.add(item.id))) return null
        }

        channels.forEach { channel -> state.appended[channel] = appended(channel) }
        state.latest = maxOf(latest ?: Long.MIN_VALUE, all.maxOf { it.timestamp })
        // Same start as a rebuild: the first row of an empty list is unshaded.
        var alternate = state.lastShown?.alternate ?: true
        for (item in added) {
            alternate = !alternate
            state.add(GroupRow(item, if (item.alternate == alternate) item else item.copy(alternate = alternate)))
        }
        state.trimTo(state.limit)
        return state.publish()
    }

    /**
     * [lists] merged by time. Each list keeps its own order even where its times are out of order;
     * merging only picks which list comes next, ties go to the first.
     */
    private fun mergeByTime(lists: List<List<ChatItem>>): List<ChatItem> {
        val merged = ArrayList<ChatItem>(lists.sumOf { it.size })
        val at = IntArray(lists.size)
        while (true) {
            var next = -1
            for (i in lists.indices) {
                if (at[i] == lists[i].size) continue
                if (next < 0 || lists[i][at[i]].timestamp < lists[next][at[next]].timestamp) next = i
            }
            if (next < 0) break
            merged.add(lists[next][at[next]++])
        }
        return merged
    }

    /** A message as the combined chat shows it, and the channel message it came from. */
    private class GroupRow(val source: ChatItem, val shown: ChatItem)

    /**
     * A combined chat as last published and the state of its channels then; what [appendToGroup]
     * needs to decide whether it can append.
     */
    private class GroupState(
        val channels: List<String>,
        val showDeleted: Boolean,
        val limit: Int,
        appended: Map<String, Long>,
        val reshaped: Map<String, Long>,
    ) {
        val appended = HashMap(appended)
        private val rows = ArrayDeque<GroupRow>()
        val byId = HashMap<String, GroupRow>()
        /** Shared ids of the Shared Chat messages shown. */
        val shared = HashSet<String>()
        /** The list last published. */
        var published: List<ChatItem> = emptyList()
            private set

        /**
         * The latest message time seen across the channels, shown or not. Kept when old messages
         * go, which can only cause extra rebuilds, never wrong results.
         */
        var latest: Long? = null

        val lastShown: ChatItem? get() = rows.lastOrNull()?.shown

        fun add(row: GroupRow) {
            rows.addLast(row)
            byId[row.source.id] = row
            row.source.sharedId?.let { shared += it }
        }

        fun trimTo(limit: Int) {
            while (rows.size > limit) {
                val row = rows.removeFirst()
                byId.remove(row.source.id)
                row.source.sharedId?.let { shared -= it }
            }
        }

        /** A new list in display order; preparation happens before publication. */
        fun publish(): List<ChatItem> {
            val out = rows.map { it.shown }
            published = out
            return out
        }
    }

    /** Only worker-built bodies reach the UI, with older rows filled in between incoming messages. */
    private fun publishSnapshot(channel: String, flow: MutableStateFlow<List<ChatItem>>, items: List<ChatItem>) {
        preparationJobs.remove(channel)?.cancel()
        items.takeLast(INITIAL_PREPARATION_COUNT).forEach { it.body.prepare() }
        publishPrepared(flow, items)
        if (items.all { it.body.isPrepared }) return
        preparationJobs[channel] = scope.launch(worker) {
            var remaining = PREPARATION_CHUNK_SIZE
            for (i in items.lastIndex downTo 0) {
                if (items[i].body.isPrepared) continue
                if (remaining == PREPARATION_CHUNK_SIZE) {
                    delay(1)
                    if (flow.subscriptionCount.value == 0) return@launch
                }
                items[i].body.prepare()
                remaining--
                if (remaining == 0) {
                    publishPrepared(flow, items)
                    remaining = PREPARATION_CHUNK_SIZE
                }
            }
            publishPrepared(flow, items)
        }
    }

    private fun publishPrepared(flow: MutableStateFlow<List<ChatItem>>, items: List<ChatItem>) {
        // Keep an already displayed body until its replacement is ready, preserving scroll anchors.
        val previous = flow.value.associateBy { it.id }
        flow.value = items.mapNotNull { item ->
            when {
                item.body.isPrepared -> item
                else -> previous[item.id]?.let { item.copy(body = it.body) }
            }
        }
    }

    private fun publishUnread() {
        val counts = counts.entries.filter { it.value.unread > 0 }.associate { it.key to it.value.unread }
        _unreadMessages.value = counts
        _channelsWithNews.value = counts.keys
    }

    private fun markDirty(channel: String) {
        dirty.add(channel)
        groupsOf[channel]?.let { dirty.addAll(it) }
        if (publishJob?.isActive == true) return
        // With no UI collecting there is nothing to publish; subscribing marks the channel dirty
        // again (see [messages]).
        if (flows.values.none { it.subscriptionCount.value > 0 }) return
        publishJob = scope.launch(worker) {
            delay(PUBLISH_INTERVAL_MS)
            val iterator = dirty.iterator()
            while (iterator.hasNext()) {
                val channel = iterator.next()
                val flow = flows[channel]
                // Nobody is collecting: stay dirty and publish on subscription.
                if (flow == null || flow.subscriptionCount.value == 0) continue
                publishSnapshot(channel, flow, snapshot(channel))
                iterator.remove()
            }
            if (unreadDirty) {
                unreadDirty = false
                publishUnread()
            }
        }
    }

    private companion object {
        const val INITIAL_PREPARATION_COUNT = 100
        const val PREPARATION_CHUNK_SIZE = 50

        /**
         * How often a channel's list is replaced. Each publish copies the whole buffer and gives
         * Compose a new list to diff; at every frame that was thirty lists of up to 500 messages a
         * second. Ten times a second still looks continuous.
         */
        const val PUBLISH_INTERVAL_MS = 100L

        /** How far apart an echo and Twitch's copy of it may be timed, both on Twitch's clock. */
        const val ECHO_MATCH_MS = 30_000L
    }
}
