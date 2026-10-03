package dev.chatter.app.net

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A request always completes, whatever the server does with the connection. */
class HttpTest {
    private val server = ServerSocket(0)
    private val http = OkHttpClient()

    /** Answers one request with [response] verbatim and closes the connection. */
    private fun answerOnce(response: String) = thread {
        server.accept().use { socket ->
            val input = socket.getInputStream().bufferedReader()
            while (input.readLine()?.isNotEmpty() == true) Unit
            socket.getOutputStream().apply {
                write(response.toByteArray())
                flush()
            }
        }
    }

    private fun request() = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()

    @After
    fun close() = server.close()

    @Test
    fun aBodyCutOffHalfwayFailsTheRequestInsteadOfLeavingItWaiting() {
        answerOnce("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{\"half\":")
        runBlocking {
            try {
                withTimeout(5_000) { http.fetch(request()) }
                fail("a body that never arrived cannot be a result")
            } catch (e: IOException) {
                // Expected: the read failed and the caller is told.
            }
        }
    }

    @Test
    fun anErrorKeepsTheStartOfWhatTheServerSaid() {
        val body = "x".repeat(10_000)
        answerOnce("HTTP/1.1 503 Service Unavailable\r\nContent-Length: ${body.length}\r\n\r\n$body")
        runBlocking {
            try {
                withTimeout(5_000) { http.fetch(request()) }
                fail("a 503 is not a result")
            } catch (e: HttpException) {
                assertEquals(503, e.code)
                assertTrue(e.body.length <= 200)
            }
        }
    }

    @Test
    fun aGoodAnswerIsTheBody() {
        answerOnce("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        assertEquals("ok", runBlocking { withTimeout(5_000) { http.fetch(request()) } })
    }
}
