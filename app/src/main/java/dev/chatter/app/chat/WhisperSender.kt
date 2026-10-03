package dev.chatter.app.chat

import android.content.Context
import dev.chatter.app.R
import dev.chatter.app.auth.AuthRepository
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HttpException
import kotlinx.coroutines.CancellationException

/** Result of sending a whisper; [message] is always worth showing. */
data class WhisperResult(val sent: Boolean, val message: String)

/**
 * Sends whispers through Helix. Two conditions cannot be checked beforehand: the token needs the
 * whisper scope and the account a verified phone number. Both only show up as a refusal, so every
 * failure is turned into a sentence saying what to do.
 */
class WhisperSender(
    private val context: Context,
    private val helix: HelixApi,
    private val auth: AuthRepository,
) {
    /**
     * Whispers [message] to [login]. [userId] saves the lookup where it is already known, e.g. from
     * a received whisper.
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
     * Twitch puts the reason for a refusal in the body; "recipient does not allow whispers" and
     * "sender is not verified" look the same otherwise.
     */
    private fun explain(e: HttpException): String = when {
        // Logging in again fixes both a missing scope and an expired token.
        e.code == 401 -> context.getString(R.string.whisper_error_scope)
        e.code == 403 -> context.getString(R.string.whisper_error_refused, e.apiMessage.orEmpty())
        e.code == 429 -> context.getString(R.string.whisper_error_rate)
        else -> context.getString(R.string.cmd_error_generic, e.apiMessage ?: "HTTP ${e.code}")
    }

    private fun failed(res: Int, vararg args: Any) =
        WhisperResult(sent = false, message = context.getString(res, *args))
}
