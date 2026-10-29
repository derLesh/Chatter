package dev.chatter.app.chat

import android.util.Log
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.auth.TwitchScopes
import dev.chatter.app.net.HelixApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Titles of the channel point rewards in the user's own channel, for the line above a message
 * entered for one. Twitch names rewards to the broadcaster only, so other channels show none.
 */
class RewardNames(
    private val helix: HelixApi,
    private val auth: AuthRepository,
    private val scope: CoroutineScope,
) {
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Reward titles by reward id. */
    val names: StateFlow<Map<String, String>> = _names

    /** Epoch millis; read and written on the chat worker only. */
    private var lastAsked = 0L

    /**
     * Asks Twitch for the titles when [rewardId], redeemed in [channelId], is a reward of the
     * user's that is not known yet. Called on the chat worker.
     */
    fun seen(rewardId: String, channelId: String?) {
        val account = auth.account ?: return
        if (channelId != account.userId || rewardId in _names.value) return
        if (!TwitchScopes.allows(account.scopes, SCOPE)) return
        // A channel without channel points answers 403 every time.
        val now = System.currentTimeMillis()
        if (now - lastAsked < RETRY_MS) return
        lastAsked = now
        scope.launch {
            val rewards = try {
                helix.customRewards(account.userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "No reward names: ${e.message}")
                return@launch
            }
            _names.update { names -> names + rewards.associate { it.id to it.title } }
        }
    }

    fun clear() {
        _names.value = emptyMap()
        lastAsked = 0L
    }

    private companion object {
        const val TAG = "RewardNames"
        const val SCOPE = "channel:read:redemptions"
        const val RETRY_MS = 60_000L
    }
}
