package dev.chatter.app.emotes

import android.util.Log
import dev.chatter.app.chat.CheerSource
import dev.chatter.app.chat.MessageBuilder
import dev.chatter.app.net.HelixCheermote
import dev.chatter.app.net.NotLoggedInException
import dev.chatter.app.net.ServiceTrouble
import dev.chatter.app.net.TrustedImages
import java.util.concurrent.ConcurrentHashMap

/** Twitch's cheermotes, as the cheermote repository needs them. */
fun interface TwitchCheermoteApi {
    /** The global cheermotes and those of [channelId]. */
    suspend fun cheermotes(channelId: String): List<HelixCheermote>
}

/** From [minBits] on, a cheermote has this picture and color. */
data class CheermoteTier(val minBits: Int, val color: Int, val darkUrl: String, val lightUrl: String)

/** A prefix like "Cheer" that stands for bits when an amount follows; [tiers] ascending. */
data class Cheermote(val prefix: String, val tiers: List<CheermoteTier>) {
    /** The highest tier [amount] reaches, or null below the first. */
    fun tierFor(amount: Int): CheermoteTier? = tiers.lastOrNull { amount >= it.minBits }

    companion object {
        /** Twitch's answer as a cheermote, without tiers whose picture is not on Twitch's hosts. */
        fun from(helix: HelixCheermote): Cheermote? {
            val tiers = helix.tiers.mapNotNull { tier ->
                fun animated(theme: String) = TrustedImages.url(tier.images[theme]?.get("animated")?.get("2").orEmpty())
                val dark = animated("dark") ?: return@mapNotNull null
                val color = MessageBuilder.parseColor(tier.color) ?: return@mapNotNull null
                CheermoteTier(tier.minBits, color, dark, animated("light") ?: dark)
            }.sortedBy { it.minBits }
            return if (helix.prefix.isEmpty() || tiers.isEmpty()) null else Cheermote(helix.prefix, tiers)
        }
    }
}

/**
 * The cheermotes of every joined channel, Twitch's global ones included, looked up by prefix while
 * messages with bits are built. Loaded on join like the badges.
 */
class CheermoteRepository(
    private val helix: TwitchCheermoteApi,
    private val trouble: ServiceTrouble = ServiceTrouble(),
) : CheerSource {
    /** By channel id, then by lowercase prefix: Twitch matches "cheer100" as well as "Cheer100". */
    private val channels = ConcurrentHashMap<String, Map<String, Cheermote>>()
    private val failedChannels = ConcurrentHashMap.newKeySet<String>()

    override fun lookup(channelId: String?, prefix: String): Cheermote? =
        channelId?.let { channels[it] }?.get(prefix.lowercase())

    suspend fun loadChannel(channelId: String) {
        if (channels.containsKey(channelId)) return
        runCatching { helix.cheermotes(channelId) }
            .onSuccess { list ->
                channels[channelId] = list.mapNotNull(Cheermote::from).associateBy { it.prefix.lowercase() }
                failedChannels.remove(channelId)
            }
            .onFailure {
                failedChannels.add(channelId)
                if (it !is NotLoggedInException) trouble.report(ServiceTrouble.TWITCH)
                Log.w(TAG, "Cheermotes failed: ${it.message}")
            }
    }

    /** Fetches again what failed to load; called when the app comes back. */
    suspend fun retryMissing() {
        failedChannels.toList().forEach { loadChannel(it) }
    }

    private companion object {
        const val TAG = "CheermoteRepository"
    }
}
