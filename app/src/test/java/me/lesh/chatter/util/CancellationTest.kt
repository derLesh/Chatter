package me.lesh.chatter.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationTest {
    @Test
    fun aFailureIsCaught() {
        val result = runCatchingCancellable<Int> { error("no answer") }
        assertEquals("no answer", result.exceptionOrNull()?.message)
    }

    @Test
    fun aValueIsKept() {
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
    }

    @Test
    fun aCancelledCoroutineStopsInsteadOfGoingOnWithAFailure() = runTest {
        var wentOn = false
        val search = async {
            runCatchingCancellable { awaitCancellation() }
            wentOn = true
        }
        yield()
        search.cancel()
        try {
            search.await()
        } catch (_: CancellationException) {
        }
        assertTrue(search.isCancelled)
        assertFalse(wentOn)
    }
}
