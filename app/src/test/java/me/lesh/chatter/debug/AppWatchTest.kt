package me.lesh.chatter.debug

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** What the log says when a setting changes. */
class AppWatchTest {
    private data class Example(val loadHistory: Boolean, val limit: Int, val keywords: List<String>)

    @Before
    fun startEmpty() = AppLog.clear()

    @Test
    fun switchesAndNumbersShowTheirValueWordsOnlyInTheFullLog() {
        val changes = AppWatch.changes(Example(true, 500, emptyList()), Example(false, 500, listOf("myname")))
        changes.forEach { AppLog.i("Settings", it) }
        val (history, keywords) = AppLog.entries()
        assertEquals("loadHistory = false", history.private)
        assertEquals("keywords changed", keywords.private)
        assertEquals("keywords = [myname]", keywords.full)
    }
}
