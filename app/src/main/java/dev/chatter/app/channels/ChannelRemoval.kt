package dev.chatter.app.channels

/**
 * Everything set on the channels as [ChannelRepository] stores it: page order, combined chats,
 * custom names, and which channels are muted or hidden from the title bar.
 */
internal data class ChannelLists(
    val pages: List<String>,
    val groups: List<ChannelGroup>,
    val names: Map<String, String>,
    val muted: Set<String>,
    val hiddenUnread: Set<String>,
)

/**
 * What a removal took, so it can be undone: besides the page, a channel's name, its notification
 * and title-bar settings, its places in combined chats, and combined chats it left empty.
 */
class RemovedPage internal constructor(
    /** The removed channel login or combined chat key. */
    val page: String,
    /** All removed pages: the requested one and combined chats it left empty. */
    internal val gone: Set<String>,
    /** The page order before, to find each page's neighbour again. */
    internal val before: List<String>,
    internal val name: String?,
    internal val muted: Boolean,
    internal val hiddenUnread: Boolean,
    /** Combined chats that remain without the channel, by id, with its position in each. */
    internal val memberships: Map<String, Int>,
    /** Combined chats removed entirely: the requested one, or those the channel left empty. */
    internal val groups: List<ChannelGroup>,
)

/** Takes [page] off the lists. Returns null if it is not there. */
internal fun ChannelLists.remove(page: String): Pair<ChannelLists, RemovedPage>? {
    if (page !in pages) return null
    if (ChannelGroup.isKey(page)) {
        val group = groups.firstOrNull { it.key == page }
        val removed = RemovedPage(
            page = page,
            gone = setOf(page),
            before = pages,
            name = null, muted = false, hiddenUnread = false,
            memberships = emptyMap(),
            groups = listOfNotNull(group),
        )
        return copy(pages = pages - page, groups = groups.filter { it.key != page }) to removed
    }
    // A combined chat without the channel keeps the others, and goes when none are left.
    val (emptied, kept) = groups.filter { page in it.channels }.partition { it.channels.size == 1 }
    val emptiedKeys = emptied.map { it.key }.toSet()
    val removed = RemovedPage(
        page = page,
        gone = emptiedKeys + page,
        before = pages,
        name = names[page],
        muted = page in muted,
        hiddenUnread = page in hiddenUnread,
        memberships = kept.associate { it.id to it.channels.indexOf(page) },
        groups = emptied,
    )
    val lists = copy(
        pages = pages.filter { it != page && it !in emptiedKeys },
        groups = groups.filter { it.key !in emptiedKeys }.map { g -> g.copy(channels = g.channels - page) },
        names = names - page,
        muted = muted - page,
        hiddenUnread = hiddenUnread - page,
    )
    return lists to removed
}

/**
 * Puts back what [removed] took, as close to its old place as possible. Changes made in between
 * stay: added pages keep their place, combined chats deleted since are not recreated.
 */
internal fun ChannelLists.putBack(removed: RemovedPage): ChannelLists {
    // Each page goes back behind the neighbour it had, if that is still there; old indices would be
    // wrong after other changes.
    val list = pages.toMutableList()
    removed.before.forEachIndexed { index, page ->
        if (page !in removed.gone || page in list) return@forEachIndexed
        val anchor = removed.before.subList(0, index).lastOrNull { it in list }
        list.add(if (anchor == null) 0 else list.indexOf(anchor) + 1, page)
    }
    val existing = groups.map { it.id }.toSet()
    val restoredGroups = groups.map { g ->
        val at = removed.memberships[g.id]
        if (at == null || removed.page in g.channels) g
        else g.copy(channels = g.channels.toMutableList().apply { add(at.coerceAtMost(size), removed.page) })
    } + removed.groups.filter { it.id !in existing }
    val login = removed.page.takeUnless(ChannelGroup::isKey)
    return copy(
        pages = list,
        groups = restoredGroups,
        names = if (login != null && removed.name != null) names + (login to removed.name) else names,
        muted = if (login != null && removed.muted) muted + login else muted,
        hiddenUnread = if (login != null && removed.hiddenUnread) hiddenUnread + login else hiddenUnread,
    )
}
