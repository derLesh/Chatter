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
        windows.setVisible(app, visible = true, channel = "forsen")
        windows.setVisible(bubble, visible = true, channel = "moondye7")
        assertTrue(windows.anyVisible.value)

        windows.setVisible(app, visible = false, channel = "forsen")
        assertTrue("the bubble is still up", windows.anyVisible.value)

        windows.setVisible(bubble, visible = false, channel = "moondye7")
        assertFalse(windows.anyVisible.value)
    }

    @Test
    fun aChannelCountsAsWatchedWhileAnyWindowShowsIt() {
        windows.setVisible(app, visible = true, channel = "forsen")
        windows.setVisible(bubble, visible = true, channel = "moondye7")
        assertTrue(windows.isWatching("forsen"))
        assertTrue(windows.isWatching("moondye7"))
        assertFalse(windows.isWatching("xqc"))
    }

    @Test
    fun aWindowThatLeftIsNotWatchingAnything() {
        windows.setVisible(app, visible = true, channel = "forsen")
        windows.setVisible(app, visible = false, channel = "forsen")
        assertFalse(windows.isWatching("forsen"))
    }

    @Test
    fun theOtherWindowKeepsItsChannelWhenOneMovesOn() {
        windows.setVisible(app, visible = true, channel = "forsen")
        windows.setVisible(bubble, visible = true, channel = "moondye7")
        windows.setChannel(app, "xqc")
        assertFalse(windows.isWatching("forsen"))
        assertTrue("the bubble did not move", windows.isWatching("moondye7"))
        assertTrue(windows.isWatching("xqc"))
    }
}
