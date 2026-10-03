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
    /** What Twitch lets the token do, as `/oauth2/validate` said; null for older entries. */
    val scopes: List<String>? = null,
)

/** What came of opening one stored account; see [AccountStore.readAll]. */
sealed interface Opened<out T> {
    class Readable<T>(val value: T) : Opened<T>

    /** Its token can never be read again. */
    data object Lost : Opened<Nothing>

    /** Its token could not be read this time; nothing is wrong with it. */
    data object Unavailable : Opened<Nothing>
}

/**
 * The stored list of accounts as one preference value, and the rules for changing it. Kept apart
 * from [AuthRepository] because none of it needs Android: this is what the tests get at.
 */
object AccountStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The accounts that could be read, in the order they were stored, and those set aside. */
    class Read<T>(val readable: List<T>, val unreadable: List<StoredAccount>)

    /**
     * Opens every stored account with [open], asking again for the ones it could not open this
     * time: the keystore that decrypts the tokens is a system service, and what keeps it from
     * answering is usually over within seconds. [wait] is called before each further attempt.
     * Those still unopened after [attempts] tries are set aside rather than forgotten — and an
     * account whose token is lost for good is in neither list.
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

    /** Junk (a half-written file, a format from the future) reads as no accounts at all. */
    fun decode(text: String?): List<StoredAccount> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredAccount>>(text) }.getOrDefault(emptyList())
    }

    /** The tokens waiting to be revoked, each one still encrypted. */
    fun encodeTokens(tokens: List<String>): String = json.encodeToString(tokens.distinct())

    /** Junk reads as nothing queued: a token that cannot be read cannot be revoked either. */
    fun decodeTokens(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
    }

    /**
     * Which of [userIds] the app acts as, given the stored [active] one: that one while it is
     * still there, and otherwise the first of the list — an account whose entry went missing
     * leaves the app on another account rather than on the login screen.
     */
    fun activeIn(userIds: List<String>, active: String?): String? =
        userIds.firstOrNull { it == active } ?: userIds.firstOrNull()
}
