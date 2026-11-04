package me.lesh.chatter.eventsub

import me.lesh.chatter.auth.AuthRepository
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.chat.Rooms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps [client] subscribed to what [EventSubPlan] says the active account needs, as channels are
 * joined and roles change.
 */
class EventSubFeeds(
    private val client: EventSubClient,
    private val auth: AuthRepository,
    private val rooms: Rooms,
    /** Which joined channels go first; see [me.lesh.chatter.chat.ChatRepository.priority]. */
    private val priority: (channel: String) -> Int,
    private val scope: CoroutineScope,
) {
    private data class Login(val userId: String, val scopes: Set<String>?)

    @OptIn(FlowPreview::class)
    fun start() = scope.launch {
        val login = auth.state
            .map { (it as? AuthState.LoggedIn)?.account?.let { a -> Login(a.userId, a.scopes) } }
            .distinctUntilChanged()
        combine(login, rooms.moderated) { account, moderated ->
            account?.let {
                it.userId to EventSubPlan.subscriptions(
                    userId = it.userId,
                    scopes = it.scopes,
                    moderated = moderated.sortedBy(priority).mapNotNull(rooms::id),
                )
            }
        }
            // Joining many channels at start changes roles one by one.
            .debounce(SETTLE_MS)
            .distinctUntilChanged()
            .collect { plan -> client.update(plan?.first, plan?.second.orEmpty()) }
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
