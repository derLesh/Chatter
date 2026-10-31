package me.lesh.chatter.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentWearersTest {
    @Test
    fun theUserSeenLongestAgoMakesRoomForANewOne() {
        val wearers = RecentWearers(max = 2)
        wearers["1"] = "a"
        wearers["2"] = "b"
        wearers["3"] = "c"
        assertNull(wearers["1"])
        assertEquals("b", wearers["2"])
        assertEquals("c", wearers["3"])
        assertEquals(2, wearers.size)
    }

    @Test
    fun aUserWhoKeepsChattingStaysIn() {
        val wearers = RecentWearers(max = 2)
        wearers["1"] = "a"
        wearers["2"] = "b"
        wearers["1"]
        wearers["3"] = "c"
        assertEquals("a", wearers["1"])
        assertNull(wearers["2"])
    }

    @Test
    fun takingOffAnOldCosmeticKeepsTheNewOne() {
        val wearers = RecentWearers(max = 10)
        wearers["1"] = "new"
        wearers.remove("1", "old")
        assertEquals("new", wearers["1"])
        wearers.remove("1", "new")
        assertNull(wearers["1"])
    }
}
