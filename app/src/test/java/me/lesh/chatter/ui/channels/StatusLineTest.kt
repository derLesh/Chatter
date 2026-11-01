package me.lesh.chatter.ui.channels

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusLineTest {
    @Test
    fun aPartThatLeavesStaysWhereItWas() {
        assertEquals(listOf("subs", "unique"), keepLeaving(listOf("subs", "unique"), listOf("unique")))
        assertEquals(listOf("subs", "unique"), keepLeaving(listOf("subs", "unique"), listOf("subs")))
        assertEquals(listOf("slow", "subs", "unique"), keepLeaving(listOf("slow", "subs", "unique"), listOf("slow", "unique")))
    }

    @Test
    fun newPartsKeepTheirOwnOrder() {
        assertEquals(listOf("subs", "unique"), keepLeaving(listOf("subs"), listOf("subs", "unique")))
        assertEquals(listOf("slow", "subs"), keepLeaving(listOf("subs"), listOf("slow", "subs")))
    }

    @Test
    fun partsComingAndGoingAtOnceKeepTheirPlaces() {
        assertEquals(listOf("slow", "subs", "unique"), keepLeaving(listOf("slow", "unique"), listOf("subs", "unique")))
        assertEquals(listOf("connection", "subs"), keepLeaving(listOf("connection"), listOf("subs")))
    }
}
