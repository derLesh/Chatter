package dev.chatter.app.channels

/**
 * Everything the user set on their channels, as [ChannelRepository] stores it: the pages in their
 * order, the combined chats, the names given to channels, and which channels do not notify or stay
 * out of the title bar.
 */
internal data class ChannelLists(
    val pages: List<String>,
    val groups: List<ChannelGroup>,
    val names: Map<String, String>,
    val muted: Set<String>,
    val hiddenUnread: Set<String>,
)

/**
 * What removing a page took with it, so that it can be put back.
 *
 * Removing a channel takes more than the channel: its name, its notification and title-bar
 * settings, its place in every combined chat — and a combined chat it leaves empty goes too. A
 * slip in the menu should cost none of that, so all of it is kept here until the undo has passed.
 */
class RemovedPage internal constructor(
    /** The channel login or the combined chat's key that was removed. */
    val page: String,
    /** Every page that went: the one asked for, and the combined chats it left empty. */
    internal val gone: Set<String>,
    /** The pages as they stood before, to find each one's neighbour again. */
    internal val before: List<String>,
    internal val name: String?,
    internal val muted: Boolean,
    internal val hiddenUnread: Boolean,
    /** The combined chats that kept going without the channel, by id, with its place in each. */
    internal val memberships: Map<String, Int>,
    /** Combined chats that went as a whole: the one asked for, or those the channel left empty. */
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
    // A combined chat without the channel reads from the ones it has left, and goes when there
    // are none.
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
 * Puts back what [removed] took, as close to where it was as the lists still allow. Whatever the
 * user did in between stays: a page added since keeps its place, a combined chat deleted since is
 * not brought back to life only to hold the channel again.
 */
internal fun ChannelLists.putBack(removed: RemovedPage): ChannelLists {
    // Each goes back behind the page it last stood behind that is still there, rather than at
    // its old index: pages added or removed since would make that a different place.
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
