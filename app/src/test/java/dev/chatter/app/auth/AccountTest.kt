package dev.chatter.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {
    private val account = Account(
        login = "lesh", userId = "60579280", token = "secret-access", refreshToken = "secret-refresh",
        expiresAt = 0L, displayName = "Lesh",
    )

    /** Account strings end up in logs and bug reports. */
    @Test
    fun theTokensNeverEndUpInAString() {
        listOf(account.toString(), AuthState.LoggedIn(account).toString()).forEach { text ->
            assertFalse(text, "secret" in text)
            assertTrue(text, "lesh" in text && "60579280" in text)
        }
    }

    @Test
    fun aMissingRefreshTokenStillSaysSo() {
        assertTrue("refreshToken=null" in account.copy(refreshToken = null).toString())
    }
}
