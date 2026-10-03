package dev.chatter.app.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Removing a channel or a combined chat, and taking that back. */
class ChannelRemovalTest {
    private val both = ChannelGroup("a1b2c3d4", "Both", listOf("forsen", "xqc"))
    private val alone = ChannelGroup("e5f6a7b8", "", listOf("forsen"))
    private val lists = ChannelLists(
        pages = listOf("xqc", both.key, "forsen", alone.key, "lirik"),
        groups = listOf(both, alone),
        names = mapOf("forsen" to "Forsen!", "lirik" to "L"),
        muted = setOf("forsen", "lirik"),
        hiddenUnread = setOf("forsen"),
    )

    @Test
    fun removingAChannelTakesEverythingSetOnIt() {
        val (after, _) = lists.remove("forsen")!!
        assertEquals("the combined chat it left empty goes too", listOf("xqc", both.key, "lirik"), after.pages)
        assertEquals(listOf(both.copy(channels = listOf("xqc"))), after.groups)
        assertEquals(mapOf("lirik" to "L"), after.names)
        assertEquals(setOf("lirik"), after.muted)
        assertEquals(emptySet<String>(), after.hiddenUnread)
    }

    @Test
    fun undoPutsAllOfItBackWhereItWas() {
        val (after, removed) = lists.remove("forsen")!!
        assertEquals(lists, after.putBack(removed))
    }

    @Test
    fun undoKeepsWhatWasDoneInBetween() {
        val (after, removed) = lists.remove("forsen")!!
        val meanwhile = after.copy(pages = listOf("new") + after.pages)
        val back = meanwhile.putBack(removed)
        assertEquals(listOf("new", "xqc", both.key, "forsen", alone.key, "lirik"), back.pages)
    }

    @Test
    fun aCombinedChatDeletedInBetweenIsNotBroughtBack() {
        val (after, removed) = lists.remove("forsen")!!
        val (withoutBoth, _) = after.remove(both.key)!!
        val back = withoutBoth.putBack(removed)
        assertEquals(listOf(alone), back.groups)
        assertEquals(listOf("xqc", "forsen", alone.key, "lirik"), back.pages)
    }

    @Test
    fun theChannelGoesBackToItsPlaceInTheCombinedChat() {
        val (after, removed) = lists.remove("forsen")!!
        val grown = after.copy(groups = after.groups.map { it.copy(channels = it.channels + "lirik") })
        assertEquals(listOf("forsen", "xqc", "lirik"), grown.putBack(removed).groups.first { it.id == both.id }.channels)
    }

    @Test
    fun removingACombinedChatLeavesItsChannelsAndUndoBringsItBack() {
        val (after, removed) = lists.remove(both.key)!!
        assertEquals(listOf("xqc", "forsen", alone.key, "lirik"), after.pages)
        assertEquals(listOf(alone), after.groups)
        assertEquals(lists.names, after.names)
        val back = after.putBack(removed)
        assertEquals(lists.pages, back.pages)
        assertEquals(setOf(both, alone), back.groups.toSet())
    }

    @Test
    fun undoingTwiceChangesNothingTheSecondTime() {
        val (after, removed) = lists.remove("forsen")!!
        val once = after.putBack(removed)
        assertEquals(once, once.putBack(removed))
    }

    @Test
    fun whatIsNotThereCannotBeRemoved() {
        assertNull(lists.remove("nobody"))
    }
}
