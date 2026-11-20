package me.lesh.chatter.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.lesh.chatter.auth.Account
import me.lesh.chatter.auth.TwitchScopes
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.emotes.ChannelEmoteSet
import me.lesh.chatter.emotes.ChannelEmoteSets
import me.lesh.chatter.emotes.SevenTvEditing
import me.lesh.chatter.net.HelixApi

/** A Twitch category, like Just Chatting. [boxArtUrl] is its cover, empty where unknown. */
data class StreamCategory(val id: String, val name: String, val boxArtUrl: String = "") {
    companion object {
        /** Twitch takes the category away for this id. */
        val NONE = StreamCategory("0", "")
    }
}

/** The title and category of the user's own channel. [category] is null without one. */
data class StreamDetails(val title: String, val category: StreamCategory?)

/** One part of the channel settings as loaded. */
sealed interface SettingsPart<out T> {
    /** The user may not change it, so it does not show. */
    data object Hidden : SettingsPart<Nothing>

    /** The user may change it, but Twitch or 7TV could not be asked. */
    data object Failed : SettingsPart<Nothing>

    /** The streamer's login is older than the permission Twitch wants; logging in again gives it. */
    data object NeedsLogin : SettingsPart<Nothing>

    data class Ready<T>(val value: T) : SettingsPart<T>
}

/** What the channel settings sheet starts from. */
data class ChannelSettingsForm(val stream: SettingsPart<StreamDetails>, val emoteSets: SettingsPart<ChannelEmoteSets>)

/** What the user changed in the channel settings; null for what stays as it was. */
data class ChannelSettingsChange(
    val title: String? = null,
    /** [StreamCategory.NONE] takes the category away. */
    val category: StreamCategory? = null,
    val emoteSet: ChannelEmoteSet? = null,
)

/**
 * What the user changed from [stream], the title and category as loaded, and from the active set:
 * [title] as typed, the [category] last picked, the category field's [query] (blank takes the
 * category away) and the [chosenSet]. A [query] that is not the picked category's name is still
 * being typed and changes nothing.
 */
fun settingsChange(
    stream: StreamDetails?,
    title: String,
    category: StreamCategory?,
    query: String,
    chosenSet: ChannelEmoteSet?,
): ChannelSettingsChange = ChannelSettingsChange(
    title = title.trim().takeIf { stream != null && it.isNotEmpty() && it != stream.title },
    category = when {
        stream == null -> null
        query.isBlank() -> StreamCategory.NONE.takeIf { stream.category != null }
        query != category?.name -> null
        category?.id != stream.category?.id -> category
        else -> null
    },
    emoteSet = chosenSet?.takeIf { !it.active },
)

/**
 * [found] in the order the user most likely means for [query]: the exact name, then names that
 * start with it, then names with a word that does, then the rest. Twitch's search finds names
 * starting with the query in no useful order, so "Just Chat" lists Just Cause before Just Chatting.
 * Within a rank Twitch's order stays.
 */
fun rankCategories(query: String, found: List<StreamCategory>): List<StreamCategory> {
    val wanted = query.trim()
    return found.sortedBy { category ->
        val name = category.name
        when {
            name.equals(wanted, ignoreCase = true) -> 0
            name.startsWith(wanted, ignoreCase = true) -> 1
            name.split(' ').any { it.startsWith(wanted, ignoreCase = true) } -> 2
            else -> 3
        }
    }
}

/**
 * What a user may change about a channel from Chatter: the title and category of their own channel,
 * and the active 7TV emote set where they are the streamer or a 7TV editor. Twitch's Mod View lets
 * mods change the title too, but through Twitch's own API; the public one takes the streamer only.
 */
class ChannelSettings(
    private val helix: HelixApi,
    /** The active account; null for a guest. */
    private val activeAccount: () -> Account?,
    private val sevenTv: SevenTvEditing,
) {
    private fun isOwn(channel: String): Boolean = activeAccount()?.login.equals(channel, ignoreCase = true)

    /** Whether the menu should offer the channel settings for [channel]; asks 7TV unless it is the user's own. */
    suspend fun mayChange(channel: String): Boolean = activeAccount() != null && (isOwn(channel) || sevenTv.maySwitchSets(channel))

    suspend fun load(channel: String): ChannelSettingsForm = coroutineScope {
        val sets = async {
            sevenTv.channelSets(channel)?.let { SettingsPart.Ready(it) }
                // The menu's answer, kept for a few minutes, tells a failed request from a user who may not.
                ?: if (sevenTv.maySwitchSets(channel)) SettingsPart.Failed else SettingsPart.Hidden
        }
        ChannelSettingsForm(stream = loadStream(channel), emoteSets = sets.await())
    }

    private suspend fun loadStream(channel: String): SettingsPart<StreamDetails> {
        val me = activeAccount()?.takeIf { isOwn(channel) } ?: return SettingsPart.Hidden
        if (!TwitchScopes.allows(me.scopes, SCOPE)) return SettingsPart.NeedsLogin
        return try {
            val info = helix.channelInfo(me.userId) ?: return SettingsPart.Failed
            val category = info.categoryId.takeIf { it.isNotEmpty() && it != StreamCategory.NONE.id }
                ?.let { StreamCategory(it, info.categoryName) }
            SettingsPart.Ready(StreamDetails(info.title, category))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not load the title and category: ${e.message}")
            SettingsPart.Failed
        }
    }

    /** Categories matching [query], best first; empty for a blank one, or if Twitch could not be asked. */
    suspend fun searchCategories(query: String): List<StreamCategory> {
        if (query.isBlank()) return emptyList()
        return try {
            val found = helix.searchCategories(query.trim(), SEARCH_PAGE).map { StreamCategory(it.id, it.name, it.boxArtUrl) }
            rankCategories(query, found).take(SHOWN_CATEGORIES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not search categories: ${e.message}")
            emptyList()
        }
    }

    /**
     * Sets the title and category of [channel], which must be the user's own; null keeps either.
     * [account] is the Twitch account they were changed for; if another one is active by now,
     * nothing is changed. False if it did not work.
     */
    suspend fun saveStream(channel: String, title: String?, category: StreamCategory?, account: String?): Boolean {
        val me = activeAccount()?.takeIf { isOwn(channel) && it.userId == account } ?: return false
        return try {
            helix.updateChannel(me.userId, title, category?.id)
            AppLog.i(TAG, "Changed the title or category of the own channel")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not change the title or category: ${e.message}")
            false
        }
    }

    private companion object {
        const val TAG = "ChannelSettings"
        const val SCOPE = "channel:manage:broadcast"

        /** Twitch's own order is poor, so more are fetched than shown and ranked here. */
        const val SEARCH_PAGE = 40
        const val SHOWN_CATEGORIES = 10
    }
}
