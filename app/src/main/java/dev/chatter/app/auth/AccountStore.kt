package dev.chatter.app.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One account the way it sits in the preferences: the two tokens encrypted (see [TokenCipher]),
 * everything else in the clear so the account switcher can show a name and a picture without
 * touching the keystore or the network.
 */
@Serializable
data class StoredAccount(
    val login: String,
    val userId: String,
    /** Encrypted. */
    val token: String,
    /** Encrypted; only accounts from the older device code login have one. */
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
    val displayName: String = "",
    val avatarUrl: String = "",
)

/**
 * The stored list of accounts as one preference value, and the rules for changing it. Kept apart
 * from [AuthRepository] because none of it needs Android: this is what the tests get at.
 */
object AccountStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(accounts: List<StoredAccount>): String = json.encodeToString(accounts)

    /** Junk (a half-written file, a format from the future) reads as no accounts at all. */
    fun decode(text: String?): List<StoredAccount> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredAccount>>(text) }.getOrDefault(emptyList())
    }

    /**
     * Which of [userIds] the app acts as, given the stored [active] one: that one while it is
     * still there, and otherwise the first of the list — an account whose entry went missing
     * leaves the app on another account rather than on the login screen.
     */
    fun activeIn(userIds: List<String>, active: String?): String? =
        userIds.firstOrNull { it == active } ?: userIds.firstOrNull()
}
