package me.lesh.chatter.ui.channels

/**
 * Maps the channel pager's pages to channels.
 *
 * Without the carousel a page is a channel and the pager stops at both ends. With it, the pager
 * gets far more pages than channels, each mapped by modulo, so swiping past the last channel
 * reaches the first without hitting an edge. Compose's pager has no wrapping mode, and jumping back
 * at the end would be visible.
 *
 * [origin] is channel 0's page, at first [ORIGIN], far enough in to wrap more often than anyone
 * swipes in one session. It moves when channels come or go; see [follow].
 */
data class ChannelPages(val channels: Int, val carousel: Boolean, val origin: Int = ORIGIN) {
    /** A single channel has nothing to wrap to. */
    val wrapping: Boolean = carousel && channels > 1

    val count: Int get() = if (wrapping) ORIGIN * 2 else channels

    /** The channel on [page], or null if there is none. */
    fun channelAt(page: Int): Int? = when {
        channels == 0 -> null
        wrapping -> (page - origin).mod(channels)
        else -> page.takeIf { it in 0 until channels }
    }

    /**
     * The page showing [channel] nearest to [from], so picking a channel does not wind the pager
     * far away.
     */
    fun pageOf(channel: Int, from: Int = origin): Int {
        if (channel !in 0 until channels) return from
        if (!wrapping) return channel
        val here = channelAt(from) ?: return from
        var steps = (channel - here).mod(channels)
        if (steps > channels / 2) steps -= channels
        return from + steps
    }

    /**
     * The pages once the list changed from [before] to [keys] with [page] on screen. That page
     * keeps its channel, or shows the one taking a removed channel's place, as without the carousel.
     * The origin moves, not the pager: scrolling it afterwards would first open whatever channel the
     * old page number now maps to.
     */
    fun follow(before: List<String>, keys: List<String>, page: Int, carousel: Boolean = this.carousel): ChannelPages {
        val next = ChannelPages(keys.size, carousel, origin)
        if (!next.wrapping) return next
        val shown = channelAt(page) ?: return next
        val kept = keys.indexOf(before.getOrNull(shown))
        val target = if (kept >= 0) kept else shown.coerceAtMost(keys.lastIndex)
        return next.copy(origin = page - target)
    }

    companion object {
        /** Half the pages of a wrapping pager; channel 0's page. */
        const val ORIGIN = 100_000
    }
}
