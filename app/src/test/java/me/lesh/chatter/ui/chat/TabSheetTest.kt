package me.lesh.chatter.ui.chat

import androidx.browser.customtabs.CustomTabsCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What of the browser's report on its tab the chat makes room for. */
class TabSheetTest {
    @Test
    fun aBottomSheetCoversTheWindowBelowItsTop() {
        val sheet = TabSheet.of(0, 802, 1080, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_BOTTOM_SHEET)
        assertEquals(SheetCover(bottom = 1602), sheet?.covered(width = 1080, height = 2404))
    }

    @Test
    fun aSideSheetOnTheRightCoversFromItsLeftEdge() {
        val sheet = TabSheet.of(1320, 0, 2400, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_SIDE_SHEET)
        assertEquals(SheetCover(right = 1080), sheet?.covered(width = 2400, height = 1080))
    }

    @Test
    fun aSideSheetOnTheLeftCoversUpToItsRightEdge() {
        val sheet = TabSheet.of(0, 0, 1080, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_SIDE_SHEET)
        assertEquals(SheetCover(left = 1080), sheet?.covered(width = 2400, height = 1080))
    }

    @Test
    fun aSheetReachingPastTheWindowCoversNoMoreThanIt() {
        val sheet = TabSheet.of(0, -50, 1080, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_BOTTOM_SHEET)
        assertEquals(SheetCover(bottom = 2404), sheet?.covered(width = 1080, height = 2404))
    }

    @Test
    fun aTabOverEverythingLeavesTheChatAsItIs() {
        assertNull(TabSheet.of(0, 0, 0, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_BOTTOM_SHEET_MAXIMIZED))
        assertNull(TabSheet.of(0, 0, 0, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_SIDE_SHEET_MAXIMIZED))
        assertNull(TabSheet.of(0, 0, 0, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_FULL_SCREEN))
        assertNull(TabSheet.of(0, 0, 0, CustomTabsCallback.ACTIVITY_LAYOUT_STATE_UNKNOWN))
    }
}
