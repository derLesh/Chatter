package dev.chatter.app.chat

import android.content.Context
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HttpException
import kotlinx.coroutines.CancellationException

/** What came of sending a whisper. [message] is always something worth showing the user. */
data class WhisperResult(val sent: Boolean, val message: String)

/**
 * Sends whispers.
 *
 * Twitch took whispers out of chat, so they go over the API instead. That brings two conditions
 * the app cannot check beforehand — its token needs the whisper scope, and the Twitch account
 * needs a verified phone number — and both only ever show up as a refusal at send time. So every
 * failure here is turned into a sentence that says what to do about it.
 */
class WhisperSender(
    private val context: Context,
    private val helix: HelixApi,
    private val auth: AuthRepository,
) {
    /**
     * Whispers [message] to [login]. [userId] is their Twitch id where it is already known — a
     * whisper carries the sender's — and saves the lookup that is otherwise needed first.
     */
    suspend fun send(login: String, userId: String?, message: String): WhisperResult {
        val me = auth.account?.userId ?: return failed(R.string.error_not_connected)
        val text = message.trim()
        if (text.isEmpty()) return failed(R.string.whisper_error_empty)
        return try {
            val target = userId ?: helix.users(listOf(login)).firstOrNull()?.id
                ?: return failed(R.string.cmd_error_no_user, login)
            helix.sendWhisper(me, target, text)
            WhisperResult(sent = true, message = context.getString(R.string.whisper_sent, login))
        } catch (e: HttpException) {
            WhisperResult(sent = false, message = explain(e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(R.string.cmd_error_generic, e.message ?: e.toString())
        }
    }

    /**
     * Twitch answers a refused whisper with the reason in the body, which is worth passing on:
     * "recipient does not allow whispers" and "sender is not verified" look the same otherwise.
     */
    private fun explain(e: HttpException): String = when {
        // Logging in again is what fixes both a token without the scope and an expired one.
        e.code == 401 -> context.getString(R.string.whisper_error_scope)
        e.code == 403 -> context.getString(R.string.whisper_error_refused, e.apiMessage.orEmpty())
        e.code == 429 -> context.getString(R.string.whisper_error_rate)
        else -> context.getString(R.string.cmd_error_generic, e.apiMessage ?: "HTTP ${e.code}")
    }

    private fun failed(res: Int, vararg args: Any) =
        WhisperResult(sent = false, message = context.getString(res, *args))
}
