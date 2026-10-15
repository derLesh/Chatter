package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.chatter.app.testing.MemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A whisper is stored for the account Twitch sent it to, however long it took to get to the store
 * and whatever happened to the accounts on the way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhisperInboxTest {
    private val active = MutableStateFlow<String?>("b")
    private val accounts = MutableStateFlow(listOf("a", "b"))

    /** Holds the next write back until [open], like a slow disk. */
    private class HeldStore(private val store: DataStore<Preferences> = MemoryStore()) : DataStore<Preferences> by store {
        private var gate: CompletableDeferred<Unit>? = CompletableDeferred()
        fun open() = gate?.complete(Unit)

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            gate?.let { it.await(); gate = null }
            return store.updateData(transform)
        }
    }

    private fun whisper(id: String, owner: String?) =
        InboxWhisper(id = id, login = "friend", displayName = "Friend", text = "secret", timestamp = 0, owner = owner)

    @Test
    fun aWhisperForTheOtherAccountStaysInItsInbox() = runTest {
        val inbox = WhisperInboxRepository(MemoryStore(), active, accounts, backgroundScope)
        inbox.add(whisper("1", owner = "a"))
        runCurrent()
        assertEquals(emptyList<InboxWhisper>(), inbox.whispers.value)

        active.value = "a"
        runCurrent()
        assertEquals(listOf("1"), inbox.whispers.value.map { it.id })
    }

    @Test
    fun aWhisperWaitingForTheStoreWhileItsAccountIsRemovedIsDropped() = runTest {
        val store = HeldStore()
        val inbox = WhisperInboxRepository(store, active, accounts, backgroundScope)
        launch { inbox.add(whisper("1", owner = "a")) }
        runCurrent()

        accounts.value = listOf("b")
        store.open()
        runCurrent()

        active.value = "a"
        runCurrent()
        assertEquals(emptyList<InboxWhisper>(), inbox.whispers.value)
    }

    @Test
    fun aWhisperWithoutAnAccountIsNotStored() = runTest {
        val inbox = WhisperInboxRepository(MemoryStore(), active, accounts, backgroundScope)
        inbox.add(whisper("1", owner = null))
        accounts.value.forEach { account ->
            active.value = account
            runCurrent()
            assertEquals(emptyList<InboxWhisper>(), inbox.whispers.value)
        }
    }
}
