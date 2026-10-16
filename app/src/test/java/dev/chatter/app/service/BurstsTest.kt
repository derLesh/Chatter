package dev.chatter.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A burst of mentions becomes a few groups. */
@OptIn(ExperimentalCoroutinesApi::class)
class BurstsTest {
    private val source = MutableSharedFlow<Int>(extraBufferCapacity = 100)

    @Test
    fun theFirstComesAtOnceAndTheRestTogether() = runTest {
        val groups = mutableListOf<List<Int>>()
        backgroundScope.launch { source.inBursts(1_000).collect { groups += it } }
        runCurrent()

        source.emit(1)
        runCurrent()
        assertEquals(listOf(listOf(1)), groups)

        (2..20).forEach { source.emit(it) }
        runCurrent()
        assertEquals("the rest waits for the window to end", 1, groups.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(listOf(1), (2..20).toList()), groups)
    }

    @Test
    fun aSteadyStreamGivesOneGroupPerWindow() = runTest {
        val groups = mutableListOf<List<Int>>()
        backgroundScope.launch { source.inBursts(1_000).collect { groups += it } }
        runCurrent()

        repeat(30) {
            source.emit(it)
            advanceTimeBy(100)
            runCurrent()
        }
        assertEquals("three seconds of mentions, one group a second", listOf(1, 9, 10, 10), groups.map { it.size })
    }

    @Test
    fun aQuietMomentEndsTheWindow() = runTest {
        val groups = mutableListOf<List<Int>>()
        backgroundScope.launch { source.inBursts(1_000).collect { groups += it } }
        runCurrent()

        source.emit(1)
        advanceTimeBy(5_000)
        runCurrent()
        source.emit(2)
        runCurrent()
        assertEquals("the next one comes at once again", listOf(listOf(1), listOf(2)), groups)
    }
}
