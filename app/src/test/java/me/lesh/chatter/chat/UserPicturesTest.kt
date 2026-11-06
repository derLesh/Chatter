package me.lesh.chatter.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserPicturesTest {
    @Test
    fun looksEachLoginUpOnce() = runTest {
        val asked = mutableListOf<List<String>>()
        val pictures = UserPictures({ logins -> asked += logins; mapOf("mila" to "https://mila.png", "bob" to "") }, backgroundScope)

        pictures.request(listOf("Mila", "bob"))
        runCurrent()
        pictures.request(listOf("mila", "bob", "flo"))
        runCurrent()

        assertEquals(listOf(listOf("mila", "bob"), listOf("flo")), asked)
        assertEquals(mapOf("mila" to "https://mila.png", "bob" to null, "flo" to null), pictures.urls.value)
    }

    @Test
    fun asksAgainAfterAFailedLookup() = runTest {
        var fail = true
        val pictures = UserPictures({ if (fail) error("offline") else mapOf("mila" to "https://mila.png") }, backgroundScope)

        pictures.request(listOf("mila"))
        runCurrent()
        assertEquals(emptyMap<String, String?>(), pictures.urls.value)

        fail = false
        pictures.request(listOf("mila"))
        runCurrent()
        assertEquals(mapOf("mila" to "https://mila.png"), pictures.urls.value)
    }
}
