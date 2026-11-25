package me.lesh.chatter.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.lesh.chatter.debug.AppLog
import me.lesh.chatter.net.HelixApi
import me.lesh.chatter.net.HelixUser
import java.time.Instant

/**
 * What the channel page says about a channel beyond its name and live status. Each part Twitch did
 * not answer is empty or null, so one failed request leaves the rest standing.
 */
data class ChannelAbout(
    val description: String = "",
    /** "partner", "affiliate" or "". */
    val broadcasterType: String = "",
    val createdAt: Instant? = null,
    val followers: Long? = null,
    /** The title set last, also while offline. */
    val title: String = "",
    val category: StreamCategory? = null,
    val tags: List<String> = emptyList(),
    /** See [StreamDetails.language]. */
    val language: String = "",
)

/** Asks Twitch what [ChannelAbout] holds. Needs a login; a guest gets null. */
class ChannelAboutSource(private val helix: HelixApi) {
    suspend fun load(login: String): ChannelAbout? = try {
        helix.users(listOf(login)).firstOrNull()?.let { about(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "Could not load the channel page of ${AppLog.channel(login)}: ${e.message}")
        null
    }

    private suspend fun about(user: HelixUser): ChannelAbout = coroutineScope {
        val followers = async { orNull { helix.followerCount(user.id) } }
        val info = orNull { helix.channelInfo(user.id) }
        ChannelAbout(
            description = user.description,
            broadcasterType = user.broadcasterType,
            createdAt = runCatching { Instant.parse(user.createdAt) }.getOrNull(),
            followers = followers.await(),
            title = info?.title.orEmpty(),
            category = info?.let { categoryOf(helix, it) },
            tags = info?.tags.orEmpty(),
            language = info?.language.orEmpty(),
        )
    }

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "Part of a channel page did not load: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "ChannelAbout"
    }
}
