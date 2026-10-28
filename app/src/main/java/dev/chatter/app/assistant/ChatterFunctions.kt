package dev.chatter.app.assistant

import android.app.PendingIntent
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDeniedException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import dev.chatter.app.AppContainer
import dev.chatter.app.ChatterApp
import dev.chatter.app.auth.AuthState
import dev.chatter.app.chat.SendLimits
import dev.chatter.app.util.appLaunchIntent
import java.time.Instant

/** A Twitch channel in the user's Chatter list. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ChatterChannel(
    /** The channel's Twitch login, lower case. Pass it as `channel` to the other functions. */
    val login: String,
    /** The name the user sees: Twitch's display name, or a name the user gave the channel. */
    val name: String,
    /** Whether the channel is streaming right now. */
    val isLive: Boolean,
    /** The stream title while live; empty otherwise. */
    val streamTitle: String,
    /** The category being streamed while live; empty otherwise. */
    val category: String,
    /** Chat messages that arrived since the user last looked at the channel. */
    val unreadMessages: Int,
    /** Messages since then that mention the user. */
    val unreadMentions: Int,
)

/** A chat message that mentioned the user. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ChatterMention(
    /** The Twitch login of the channel it was written in. */
    val channel: String,
    /** The display name of whoever wrote it. */
    val from: String,
    /** The message as written. */
    val text: String,
    /** When it arrived. */
    val sentAt: Instant,
    /** Whether the user has seen it in Chatter. */
    val read: Boolean,
)

/**
 * What Gemini and other assistants may do in Chatter, through Android's AppFunctions. Every
 * function starts disabled; the beta switch under Settings → Beta features enables them (see
 * [AssistantAccess]).
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "ChatterAppFunctionService",
    appFunctionXmlFileName = "chatter_app_functions",
)
abstract class ChatterFunctions : AppFunctionService() {
    private val container: AppContainer get() = (applicationContext as ChatterApp).container

    /**
     * List the Twitch channels the user follows in Chatter, with whether each is live and how much
     * the user has not read yet. Answers requests like "Who is live?" or "Where are unread messages?".
     *
     * @return Every channel in the user's order. Empty if the user has added none.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun listChannels(): List<ChatterChannel> {
        val c = container
        val info = c.channels.info.value
        val unreadMessages = c.chat.unreadMessages.value
        val unreadMentions = c.chat.unreadMentions.value
        return c.channels.channels.value.map { login ->
            val i = info[login]
            ChatterChannel(
                login = login,
                name = i?.displayName ?: login,
                isLive = i?.isLive == true,
                streamTitle = i?.title.orEmpty().takeIf { i?.isLive == true }.orEmpty(),
                category = i?.game.orEmpty().takeIf { i?.isLive == true }.orEmpty(),
                unreadMessages = unreadMessages[login] ?: 0,
                unreadMentions = unreadMentions[login] ?: 0,
            )
        }
    }

    /**
     * Get the chat messages that mentioned the user, newest first. Answers requests like "Any new
     * mentions?" or "What did people write to me on Twitch?".
     *
     * @param onlyUnread True for only the mentions the user has not seen yet; false for all that
     *   Chatter kept.
     * @param limit The most mentions to return, between 1 and 50.
     * @return The mentions of the account logged in to Chatter. Empty for a guest.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun listMentions(onlyUnread: Boolean = true, limit: Int = 10): List<ChatterMention> {
        if (limit !in 1..MAX_MENTIONS) throw AppFunctionInvalidArgumentException("limit must be between 1 and $MAX_MENTIONS")
        return container.inbox.mentions.value
            .filter { !onlyUnread || !it.read }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map { ChatterMention(it.channel, it.displayName, it.text, Instant.ofEpochMilli(it.timestamp), it.read) }
    }

    /**
     * Open the chat of a channel in Chatter, as in "Open xqc's chat".
     *
     * Required workflow: Call "listChannels" first to find the channel's login.
     *
     * @param channel The login of a channel from "listChannels".
     * @return An intent that shows the channel when started.
     * @throws AppFunctionElementNotFoundException If the channel is not in the user's list; offer
     *   the channels from "listChannels" instead.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun openChannel(channel: String): PendingIntent {
        val login = knownChannel(channel)
        return PendingIntent.getActivity(
            this,
            login.hashCode(),
            appLaunchIntent(this, login),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Send a chat message to a channel as the user's Twitch account. Everybody in that chat sees it
     * under the user's name. Fits requests like "Write gg in xqc" or "Tell the chat in forsen
     * that I am back".
     *
     * Required workflow: Call "listChannels" first to find the channel's login, and show the user
     * the exact message before sending it.
     *
     * @param channel The login of a channel from "listChannels", such as "xqc".
     * @param message The text to send, such as "gg", at most 500 characters. Twitch commands
     *   starting with "/" are not allowed.
     * @throws AppFunctionElementNotFoundException If the channel is not in the user's list.
     * @throws AppFunctionDeniedException If the user is not logged in or set Chatter to only read;
     *   tell them to change that in Chatter.
     * @throws AppFunctionAppUnknownException If Twitch could not be reached or refused the message;
     *   suggest trying again later.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true, isEnabled = false)
    suspend fun sendChatMessage(channel: String, message: String) {
        val text = message.trim()
        if (text.isEmpty()) throw AppFunctionInvalidArgumentException("message is empty")
        if (text.length > SendLimits.MAX_LENGTH) throw AppFunctionInvalidArgumentException("message is longer than ${SendLimits.MAX_LENGTH} characters")
        // A command could ban, mod or raid; that is not something to do on a spoken request.
        if (text.startsWith("/")) throw AppFunctionInvalidArgumentException("commands are not allowed")
        val login = knownChannel(channel)
        val c = container
        if (c.settings.settings.value.readOnly) throw AppFunctionDeniedException("Chatter is set to only read chats")
        val account = (c.auth.state.value as? AuthState.LoggedIn)?.account
            ?: throw AppFunctionDeniedException("Nobody is logged in to Chatter")
        if (!c.sendFromNotification(account.userId, login, text)) {
            throw AppFunctionAppUnknownException("The message could not be sent to Twitch")
        }
    }

    private fun knownChannel(channel: String): String {
        val login = channel.trim().removePrefix("#").removePrefix("@").lowercase()
        if (login !in container.channels.channels.value) throw AppFunctionElementNotFoundException("$channel is not in Chatter's channel list")
        return login
    }

    private companion object {
        const val MAX_MENTIONS = 50
    }
}
