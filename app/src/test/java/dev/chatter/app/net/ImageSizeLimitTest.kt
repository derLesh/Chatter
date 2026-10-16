package dev.chatter.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A picture larger than the limit fails, whether or not the server says its size. */
class ImageSizeLimitTest {
    private val server = ServerSocket(0)
    private val http = OkHttpClient.Builder().addInterceptor(ImageSizeLimit(maxBytes = 1_000)).build()

    /** Answers one request with [head] and then [body] verbatim, and closes the connection. */
    private fun answerOnce(head: String, body: ByteArray) = thread(isDaemon = true) {
        server.accept().use { socket ->
            val input = socket.getInputStream().bufferedReader()
            while (input.readLine()?.isNotEmpty() == true) Unit
            runCatching {
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    write(body)
                    flush()
                }
            }
        }
    }

    private fun fetch(): ByteArray =
        http.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/a.png").build()).execute().use { it.body.bytes() }

    @After
    fun close() = server.close()

    @Test
    fun aPictureWithinTheLimitLoads() {
        answerOnce("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n", ByteArray(1_000))
        assertEquals(1_000, fetch().size)
    }

    @Test
    fun aPictureThatSaysItIsTooLargeIsNotRead() {
        answerOnce("HTTP/1.1 200 OK\r\nContent-Length: 1001\r\nConnection: close\r\n\r\n", ByteArray(1_001))
        try {
            fetch()
            fail("a picture over the limit cannot load")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun aPictureThatDoesNotSayItsSizeStopsAtTheLimit() {
        // Without a length the body runs until the connection closes.
        answerOnce("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n", ByteArray(50_000))
        try {
            fetch()
            fail("a picture over the limit cannot load")
        } catch (e: IOException) {
            // Expected.
        }
    }
}
