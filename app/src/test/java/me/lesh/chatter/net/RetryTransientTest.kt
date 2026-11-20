package me.lesh.chatter.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/** A provider that misses one request is asked again; one whose answer will not change is not. */
class RetryTransientTest {
    private var calls = 0

    private suspend fun failing(vararg errors: Exception): String = retryTransient("test") {
        calls++
        errors.getOrNull(calls - 1)?.let { throw it }
        "answer"
    }

    private suspend fun assertThrows(expected: Class<out Exception>, block: suspend () -> Unit) {
        try {
            block()
            fail("expected ${expected.simpleName}")
        } catch (e: Exception) {
            assertEquals(expected, e.javaClass)
        }
    }

    @Test
    fun aTimeoutFollowedByAnAnswerIsTheAnswer() = runTest {
        assertEquals("answer", failing(SocketTimeoutException("timeout")))
        assertEquals(2, calls)
    }

    @Test
    fun serverErrorsAreAskedAgainUntilTheLastAttempt() = runTest {
        assertEquals("answer", failing(HttpException(502, ""), HttpException(503, "")))
        assertEquals(3, calls)
    }

    @Test
    fun theLastFailureIsThrownAfterThreeAttempts() = runTest {
        val down = IOException("down")
        assertThrows(IOException::class.java) { failing(down, down, down) }
        assertEquals(3, calls)
    }

    @Test
    fun aMissingPageIsNotAskedAgain() = runTest {
        assertThrows(HttpException::class.java) { failing(HttpException(404, "")) }
        assertEquals(1, calls)
    }

    @Test
    fun aProviderThatAsksToSlowDownIsLeftToTheLongerRetries() = runTest {
        assertThrows(HttpException::class.java) { failing(HttpException(429, "")) }
        assertEquals(1, calls)
    }

    @Test
    fun aRejectedCertificateIsNotAskedAgain() = runTest {
        val rejected = SSLHandshakeException("no").apply { initCause(CertificateException("untrusted")) }
        assertThrows(SSLHandshakeException::class.java) { failing(rejected) }
        assertEquals(1, calls)
    }

    @Test
    fun anUnreadableAnswerIsNotAskedAgain() = runTest {
        assertThrows(IllegalArgumentException::class.java) { failing(IllegalArgumentException("not json")) }
        assertEquals(1, calls)
    }

    @Test
    fun cancellationEndsItAtOnce() = runTest {
        assertThrows(CancellationException::class.java) { failing(CancellationException("left")) }
        assertEquals(1, calls)
    }
}
