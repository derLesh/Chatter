package dev.chatter.app.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One account as stored: the tokens encrypted (see [TokenCipher]), the rest in the clear so the
 * account switcher needs neither the keystore nor the network.
 */
@Serializable
data class StoredAccount(
    val login: String,
    val userId: String,
    /** Encrypted. */
    val token: String,
    /** Encrypted; only accounts from the old device code login have one. */
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
    val displayName: String = "",
    val avatarUrl: String = "",
    /** As `/oauth2/validate` reported them; null for entries from before scopes were stored. */
    val scopes: List<String>? = null,
)

/** Result of opening one stored account; see [AccountStore.readAll]. */
sealed interface Opened<out T> {
    class Readable<T>(val value: T) : Opened<T>

    /** Its token can never be read again. */
    data object Lost : Opened<Nothing>

    /** Its token could not be read this time. */
    data object Unavailable : Opened<Nothing>
}

/** The stored account list and the rules for changing it, kept free of Android for the tests. */
object AccountStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The accounts that could be read, in the order they were stored, and those set aside. */
    class Read<T>(val readable: List<T>, val unreadable: List<StoredAccount>)

    /**
     * Opens every account with [open] and retries the [Opened.Unavailable] ones, calling [wait]
     * before each further attempt; the keystore usually answers again within seconds. Accounts
     * still unavailable after [attempts] tries end up in [Read.unreadable]; lost ones are dropped.
     */
    suspend fun <T> readAll(
        stored: List<StoredAccount>,
        attempts: Int = 4,
        wait: suspend (attempt: Int) -> Unit,
        open: (StoredAccount) -> Opened<T>,
    ): Read<T> {
        val readable = HashMap<String, T>()
        var pending = stored
        for (attempt in 0 until attempts) {
            if (attempt > 0) wait(attempt)
            pending = pending.filter { entry ->
                when (val result = open(entry)) {
                    is Opened.Readable -> {
                        readable[entry.userId] = result.value
                        false
                    }
                    Opened.Lost -> false
                    Opened.Unavailable -> true
                }
            }
            if (pending.isEmpty()) break
        }
        return Read(stored.mapNotNull { readable[it.userId] }, pending)
    }

    fun encode(accounts: List<StoredAccount>): String = json.encodeToString(accounts)

    /** Unreadable text reads as no accounts. */
    fun decode(text: String?): List<StoredAccount> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredAccount>>(text) }.getOrDefault(emptyList())
    }

    /** The tokens waiting to be revoked, still encrypted. */
    fun encodeTokens(tokens: List<String>): String = json.encodeToString(tokens.distinct())

    /** Unreadable text reads as nothing queued. */
    fun decodeTokens(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
    }

    /**
     * The stored [active] account if it is still there, otherwise the first one, so a missing entry
     * does not end on the login screen.
     */
    fun activeIn(userIds: List<String>, active: String?): String? =
        userIds.firstOrNull { it == active } ?: userIds.firstOrNull()
}
