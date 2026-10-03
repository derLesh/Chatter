package dev.chatter.app.channels

import android.util.Log
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixBlockedUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The user's block list on Twitch, so blocking here and on twitch.tv is the same thing. Loaded
 * after login and updated as the user blocks and unblocks.
 */
class BlockedUsersRepository(private val helix: HelixApi, scope: CoroutineScope) {
    private val _blocked = MutableStateFlow<List<HelixBlockedUser>>(emptyList())
    val blocked: StateFlow<List<HelixBlockedUser>> = _blocked

    /** The logins in lowercase, for filtering messages. */
    val logins: StateFlow<Set<String>> = _blocked
        .map { list -> list.mapTo(HashSet()) { it.userLogin.lowercase() } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun load(userId: String) {
        runCatching { helix.blockedUsers(userId) }
            .onSuccess { list -> _blocked.value = list.sortedBy { it.userLogin } }
            .onFailure { Log.w(TAG, "Block list failed: ${it.message}") }
    }

    /** Blocks or unblocks on Twitch; the local list follows once Twitch confirmed. */
    suspend fun setBlocked(user: HelixBlockedUser, blocked: Boolean): Boolean {
        val ok = runCatching { helix.setBlocked(user.userId, blocked) }
            .onFailure { Log.w(TAG, "Blocking ${user.userLogin} failed: ${it.message}") }
            .isSuccess
        if (ok) {
            _blocked.update { list ->
                if (blocked) (list + user).distinctBy { it.userId }.sortedBy { it.userLogin }
                else list.filterNot { it.userId == user.userId }
            }
        }
        return ok
    }

    fun isBlocked(login: String): Boolean = login.lowercase() in logins.value

    fun clear() {
        _blocked.value = emptyList()
    }

    private companion object {
        const val TAG = "BlockedUsers"
    }
}
