package dev.chatter.app.badges

import android.util.Log
import dev.chatter.app.chat.BadgeSource
import dev.chatter.app.net.HelixApi
import dev.chatter.app.net.HelixBadgeSet
import java.util.concurrent.ConcurrentHashMap

data class Badge(val url: String, val title: String)

/** Resolves the `badges` IRC tag (e.g. "moderator/1,subscriber/12") to images. */
class BadgeRepository(private val helix: HelixApi) : BadgeSource {
    @Volatile private var global: Map<String, Badge> = emptyMap()
    private val channels = ConcurrentHashMap<String, Map<String, Badge>>()

    override fun resolve(channelId: String?, badgesTag: String?): List<Badge> {
        if (badgesTag.isNullOrEmpty()) return emptyList()
        val channel = channelId?.let { channels[it] }
        return badgesTag.split(',').mapNotNull { key -> channel?.get(key) ?: global[key] }
    }

    suspend fun loadGlobal() {
        if (global.isNotEmpty()) return
        runCatching { helix.globalBadges() }
            .onSuccess { global = it.toMap() }
            .onFailure { Log.w("BadgeRepository", "Global badges failed: ${it.message}") }
    }

    suspend fun loadChannel(channelId: String) {
        runCatching { helix.channelBadges(channelId) }
            .onSuccess { channels[channelId] = it.toMap() }
            .onFailure { Log.w("BadgeRepository", "Channel badges failed: ${it.message}") }
    }

    private fun List<HelixBadgeSet>.toMap(): Map<String, Badge> {
        val map = HashMap<String, Badge>()
        for (set in this) for (v in set.versions) {
            map["${set.setId}/${v.id}"] = Badge(v.url2x.ifEmpty { v.url1x }, v.title)
        }
        return map
    }
}
