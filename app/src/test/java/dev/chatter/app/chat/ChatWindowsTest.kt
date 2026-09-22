package dev.chatter.app.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWindowsTest {
    private val windows = ChatWindows()
    private val app = Any()
    private val bubble = Any()

    @Test
    fun aWindowLeavingWhileAnotherIsOpenDoesNotLookLikeNobodyIsThere() {
        windows.setVisible(app, visible = true, channels = setOf("forsen"))
        windows.setVisible(bubble, visible = true, channels = setOf("moondye7"))
        assertTrue(windows.anyVisible.value)

        windows.setVisible(app, visible = false, channels = setOf("forsen"))
        assertTrue("the bubble is still up", windows.anyVisible.value)

        windows.setVisible(bubble, visible = false, channels = setOf("moondye7"))
        assertFalse(windows.anyVisible.value)
    }

    @Test
    fun aChannelCountsAsWatchedWhileAnyWindowShowsIt() {
        windows.setVisible(app, visible = true, channels = setOf("forsen"))
        windows.setVisible(bubble, visible = true, channels = setOf("moondye7"))
        assertTrue(windows.isWatching("forsen"))
        assertTrue(windows.isWatching("moondye7"))
        assertFalse(windows.isWatching("xqc"))
    }

    @Test
    fun aWindowThatLeftIsNotWatchingAnything() {
        windows.setVisible(app, visible = true, channels = setOf("forsen"))
        windows.setVisible(app, visible = false, channels = setOf("forsen"))
        assertFalse(windows.isWatching("forsen"))
    }

    @Test
    fun theOtherWindowKeepsItsChannelWhenOneMovesOn() {
        windows.setVisible(app, visible = true, channels = setOf("forsen"))
        windows.setVisible(bubble, visible = true, channels = setOf("moondye7"))
        windows.setChannels(app, setOf("xqc"))
        assertFalse(windows.isWatching("forsen"))
        assertTrue("the bubble did not move", windows.isWatching("moondye7"))
        assertTrue(windows.isWatching("xqc"))
    }

    @Test
    fun aCombinedChatWatchesEveryOneOfItsChannels() {
        windows.setVisible(app, visible = true, channels = setOf("forsen", "xqc"))
        assertTrue(windows.isWatching("forsen"))
        assertTrue(windows.isWatching("xqc"))

        windows.setChannels(app, setOf("forsen"))
        assertFalse("the combined chat was left for a single channel", windows.isWatching("xqc"))
    }
}
