package dev.chatter.app.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A picture follows redirects on its own host only. */
class ImageRedirectsTest {
    /** Answers each request with what [answer] says for its path, one request per connection. */
    private class FakeHost(private val answer: (path: String) -> String) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port: Int get() = socket.localPort
        val asked = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use {
                        val input = it.getInputStream().bufferedReader()
                        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        while (input.readLine()?.isNotEmpty() == true) Unit
                        asked += path
                        it.getOutputStream().apply {
                            write(answer(path).toByteArray())
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun redirect(location: String) =
        "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    private val picture = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 3\r\nConnection: close\r\n\r\npng"

    private val http = OkHttpClient.Builder().followRedirects(false).addInterceptor(ImageRedirects).build()
    private val hosts = mutableListOf<FakeHost>()

    private fun host(answer: (path: String) -> String) = FakeHost(answer).also { hosts += it }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute()

    @After
    fun close() = hosts.forEach { it.close() }

    @Test
    fun aRedirectOnTheSameHostIsFollowed() {
        val imgur = host { path ->
            when (path) {
                "/gone.png" -> redirect("/moved.png")
                "/moved.png" -> redirect("/removed.png")
                else -> picture
            }
        }
        get("http://127.0.0.1:${imgur.port}/gone.png").use {
            assertEquals(200, it.code)
            assertEquals("png", it.body.string())
        }
        assertEquals(listOf("/gone.png", "/moved.png", "/removed.png"), imgur.asked)
    }

    @Test
    fun anotherHostIsNeverAsked() {
        // localhost and 127.0.0.1 are the same machine, but not the same host name.
        val elsewhere = host { picture }
        val provider = host { redirect("http://localhost:${elsewhere.port}/tracker.png") }
        get("http://127.0.0.1:${provider.port}/emote.png").use {
            assertEquals("the redirect fails the picture", 302, it.code)
        }
        assertEquals(listOf("/emote.png"), provider.asked)
        assertTrue(elsewhere.asked.isEmpty())
    }

    @Test
    fun aLoopEnds() {
        val looping = host { redirect("/again.png") }
        get("http://127.0.0.1:${looping.port}/start.png").use { assertEquals(302, it.code) }
        assertEquals(6, looping.asked.size)
    }

    @Test
    fun neverDownToHttpNorWithALogin() {
        val from = "https://i.imgur.com/a.png".toHttpUrl()
        assertTrue(ImageRedirects.allowed(from, "https://i.imgur.com/removed.png".toHttpUrl()))
        assertFalse(ImageRedirects.allowed(from, "http://i.imgur.com/removed.png".toHttpUrl()))
        assertFalse(ImageRedirects.allowed(from, "https://user:pw@i.imgur.com/removed.png".toHttpUrl()))
        assertFalse(ImageRedirects.allowed(from, "https://imgur.com/a".toHttpUrl()))
        assertFalse(ImageRedirects.allowed(from, "https://i.imgur.com.evil.example/a.png".toHttpUrl()))
        assertTrue("up to https is fine", ImageRedirects.allowed("http://i.nuuls.com/a.png".toHttpUrl(), "https://i.nuuls.com/a.png".toHttpUrl()))
    }
}
