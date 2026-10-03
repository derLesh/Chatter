package dev.chatter.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchScopesTest {
    /** Moderators can moderate right after logging in. */
    @Test
    fun theLoginAsksForModerationToo() {
        assertTrue(TwitchScopes.ALL.containsAll(listOf("chat:read", "chat:edit", "moderator:manage:banned_users")))
    }

    /** Scopes for features Chatter does not have stay out of the token. */
    @Test
    fun theLoginAsksForNothingChatterDoesNotUse() {
        listOf("channel:manage:broadcast", "channel:edit:commercial", "channel:manage:polls", "channel:read:redemptions")
            .forEach { assertFalse(it, it in TwitchScopes.ALL) }
        assertEquals(TwitchScopes.ALL.size, TwitchScopes.ALL.toSet().size)
    }

    @Test
    fun aTokenWithoutTheScopeIsNotAllowed() {
        val granted = setOf("chat:read", "chat:edit")
        assertFalse(TwitchScopes.allows(granted, "moderator:manage:banned_users"))
        assertTrue(TwitchScopes.allows(granted, "chat:edit"))
    }

    @Test
    fun anOlderLoginIsMissingWhatItWasNotGiven() {
        val granted = TwitchScopes.ALL.toSet() - "channel:manage:raids"
        assertEquals(setOf("channel:manage:raids"), TwitchScopes.missing(granted))
        assertEquals(emptySet<String>(), TwitchScopes.missing(TwitchScopes.ALL.toSet()))
    }

    /** Accounts from before scopes were stored may try; Twitch decides. */
    @Test
    fun anAccountWithUnknownScopesIsLetTry() {
        assertTrue(TwitchScopes.allows(null, "channel:manage:raids"))
        assertEquals(emptySet<String>(), TwitchScopes.missing(null))
    }
}
