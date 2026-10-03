package dev.chatter.app.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStoreTest {
    private val lesh = StoredAccount(
        login = "lesh",
        userId = "1",
        token = "encrypted-token",
        refreshToken = "encrypted-refresh",
        expiresAt = 1_700_000_000_000,
        displayName = "Lesh",
        avatarUrl = "https://example.invalid/lesh.png",
    )
    private val bot = StoredAccount(login = "leshbot", userId = "2", token = "another-token")

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        assertEquals(listOf(lesh, bot), AccountStore.decode(AccountStore.encode(listOf(lesh, bot))))
    }

    @Test
    fun anEmptyStoreReadsAsNoAccounts() {
        assertTrue(AccountStore.decode(null).isEmpty())
        assertTrue(AccountStore.decode("").isEmpty())
    }

    /** A half-written file must not take the app down; it reads as logged out instead. */
    @Test
    fun junkReadsAsNoAccounts() {
        assertTrue(AccountStore.decode("[{\"login\":\"lesh\"").isEmpty())
        assertTrue(AccountStore.decode("not json at all").isEmpty())
    }

    /** A field added by a later version of the app must not make the whole list unreadable. */
    @Test
    fun anUnknownFieldIsIgnored() {
        val text = """[{"login":"lesh","userId":"1","token":"t","favouriteColor":"green"}]"""
        assertEquals(listOf(StoredAccount(login = "lesh", userId = "1", token = "t")), AccountStore.decode(text))
    }

    @Test
    fun theStoredAccountStaysActive() {
        assertEquals("2", AccountStore.activeIn(listOf("1", "2"), "2"))
    }

    /** Whatever happened to the account that was active, the app lands on another one. */
    @Test
    fun aMissingActiveAccountFallsBackToTheFirst() {
        assertEquals("1", AccountStore.activeIn(listOf("1", "2"), "99"))
        assertEquals("1", AccountStore.activeIn(listOf("1", "2"), null))
    }

    @Test
    fun noAccountsMeansNoActiveOne() {
        assertNull(AccountStore.activeIn(emptyList(), "1"))
    }

    @Test
    fun tokensToRevokeSurviveTheRoundTrip() {
        val queued = listOf("a", "b")
        assertEquals(queued, AccountStore.decodeTokens(AccountStore.encodeTokens(queued)))
    }

    /** Logging the same account out twice before Twitch answered must not ask twice. */
    @Test
    fun aTokenIsQueuedOnce() {
        assertEquals(listOf("a"), AccountStore.decodeTokens(AccountStore.encodeTokens(listOf("a", "a"))))
    }

    @Test
    fun junkQueuesNothing() {
        assertEquals(emptyList<String>(), AccountStore.decodeTokens(null))
        assertEquals(emptyList<String>(), AccountStore.decodeTokens("{not a list"))
    }

    @Test
    fun aKeystoreThatAnswersLateStillLetsTheAccountIn() {
        var tries = 0
        val waits = mutableListOf<Int>()
        val read = runBlocking {
            AccountStore.readAll(listOf(lesh, bot), wait = { waits += it }) { entry ->
                if (entry == lesh && ++tries < 3) Opened.Unavailable else Opened.Readable(entry.login)
            }
        }
        assertEquals("in the order they were stored", listOf("lesh", "leshbot"), read.readable)
        assertTrue(read.unreadable.isEmpty())
        assertEquals(listOf(1, 2), waits)
    }

    @Test
    fun anAccountTheKeystoreNeverAnswersForIsSetAsideNotForgotten() {
        val read = runBlocking {
            AccountStore.readAll(listOf(lesh, bot), wait = {}) { entry ->
                if (entry == lesh) Opened.Unavailable else Opened.Readable(entry.login)
            }
        }
        assertEquals(listOf("leshbot"), read.readable)
        assertEquals(listOf(lesh), read.unreadable)
    }

    @Test
    fun anAccountWhoseTokenIsLostIsInNeitherList() {
        var asked = 0
        val read = runBlocking {
            AccountStore.readAll(listOf(lesh), wait = {}) { asked++; Opened.Lost }
        }
        assertTrue(read.readable.isEmpty())
        assertTrue(read.unreadable.isEmpty())
        assertEquals("a lost token is not asked about again", 1, asked)
    }
}
