package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.testing.MemoryStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mention inbox is kept in memory and written in batches. */
@OptIn(ExperimentalCoroutinesApi::class)
class MentionInboxTest {
    /** Counts the writes that reach it. */
    private class CountingStore(private val store: DataStore<Preferences> = MemoryStore()) : DataStore<Preferences> by store {
        var writes = 0

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writes++
            return store.updateData(transform)
        }
    }

    private val mentionsKey = stringPreferencesKey("inbox_mentions")

    private fun mention(id: String) =
        ChatItem(id = id, channel = "forsen", kind = MessageKind.Chat, timestamp = 0, login = "a", text = "@lukas")

    @Test
    fun aBurstOfMentionsIsWrittenOnce() = runTest {
        val store = CountingStore()
        val inbox = MentionInboxRepository(store, MutableStateFlow("1"), backgroundScope)
        repeat(20) { inbox.add(mention("$it"), read = false, account = "1") }
        runCurrent()
        assertEquals("shown at once", 20, inbox.mentions.value.size)
        assertEquals("not written yet", 0, store.writes)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, store.writes)
        assertTrue(store.data.first()[mentionsKey].orEmpty().contains("\"id\":\"19\""))
    }

    @Test
    fun savingNowWritesWithoutWaiting() = runTest {
        val store = CountingStore()
        val inbox = MentionInboxRepository(store, MutableStateFlow("1"), backgroundScope) { false }
        inbox.add(mention("m"), read = false, account = "1")
        inbox.saveNow()
        runCurrent()
        assertEquals(1, store.writes)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("nothing changed since", 1, store.writes)
    }

    @Test
    fun aStoredInboxIsReadBack() = runTest {
        val store = MemoryStore()
        val first = MentionInboxRepository(store, MutableStateFlow("1"), backgroundScope)
        first.add(mention("m"), read = false, account = "1")
        first.saveNow()
        runCurrent()

        val again = MentionInboxRepository(store, MutableStateFlow("1"), backgroundScope)
        runCurrent()
        assertEquals(listOf("m"), again.mentions.value.map { it.id })
    }
}
