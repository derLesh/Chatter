package dev.chatter.app.ui.channels

/**
 * Where the channel pager's pages are, and which channel each one shows.
 *
 * Without the carousel a page simply *is* a channel: page 2 is the third channel, and the pager
 * stops at both ends. With it, the pager is handed far more pages than there are channels and
 * every page maps back onto one by modulo — so the swipe off the last channel lands on the first
 * without the pager ever reaching an edge it could stop at. Compose's pager has no wrapping mode
 * of its own, and a jump back to the other end at the moment of the swipe would be visible.
 *
 * [ORIGIN] is the page channel 0 starts on. Starting that far in leaves room for more wraps in
 * either direction than anybody is going to swipe in one sitting.
 */
data class ChannelPages(val channels: Int, val carousel: Boolean) {
    /** A single channel has nothing to wrap around to, so it never wraps. */
    val wrapping: Boolean = carousel && channels > 1

    val count: Int get() = if (wrapping) ORIGIN * 2 else channels

    /** The channel [page] shows, or null when there is no channel there. */
    fun channelAt(page: Int): Int? = when {
        channels == 0 -> null
        wrapping -> (page - ORIGIN).mod(channels)
        else -> page.takeIf { it in 0 until channels }
    }

    /**
     * A page showing [channel]. The one nearest [from], so that picking a channel from the title
     * bar does not wind up a long way round that later swipes would have to unwind.
     */
    fun pageOf(channel: Int, from: Int = ORIGIN): Int {
        if (channel !in 0 until channels) return from
        if (!wrapping) return channel
        val here = channelAt(from) ?: return from
        var steps = (channel - here).mod(channels)
        if (steps > channels / 2) steps -= channels
        return from + steps
    }

    companion object {
        /** Half the pages of a wrapping pager, and the page channel 0 sits on. */
        const val ORIGIN = 100_000
    }
}
