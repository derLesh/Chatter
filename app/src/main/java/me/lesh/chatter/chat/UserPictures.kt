package me.lesh.chatter.chat

import me.lesh.chatter.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Profile picture URLs of chatters by lower-case login, looked up the first time they are asked
 * for: up to a hundred people in one Helix request. Kept in memory only; Coil caches the pictures.
 */
class UserPictures(
    /** The users' profile picture URLs by login; empty where they have none. */
    private val lookUp: suspend (logins: List<String>) -> Map<String, String>,
    private val scope: CoroutineScope,
) {
    private val _urls = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** Null for a known login without a picture; absent while not looked up yet. */
    val urls: StateFlow<Map<String, String?>> = _urls

    private val asked = HashSet<String>()

    fun request(logins: Collection<String>) {
        val missing = synchronized(asked) { logins.map { it.lowercase() }.filter { asked.add(it) } }
        if (missing.isEmpty()) return
        scope.launch {
            val found = runCatchingCancellable { lookUp(missing) }.getOrElse {
                // Asked again next time, rather than shown without pictures for good.
                synchronized(asked) { asked.removeAll(missing.toSet()) }
                return@launch
            }
            _urls.update { known -> known + missing.associateWith { found[it]?.ifEmpty { null } } }
        }
    }
}
