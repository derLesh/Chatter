package dev.chatter.app.auth

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
}
