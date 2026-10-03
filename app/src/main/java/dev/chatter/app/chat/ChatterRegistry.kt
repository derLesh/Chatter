package dev.chatter.app.chat

/**
 * Users known in a channel: recent chatters plus Twitch's chatter list where the user moderates.
 * Feeds name autocomplete and lets [MessageBuilder] color "@name" in that user's color.
 *
 * Only touched on [ChatRepository]'s worker.
 */
class ChatterRegistry {
    /** Display name and chat color; the color is null if they never picked one. */
    data class Chatter(val displayName: String, val color: Int?)

    private val perChannel = HashMap<String, LinkedHashMap<String, Chatter>>()

    /**
     * Everyone Twitch lists as present, by lowercase login. Twitch reports no color, so mentions of
     * them are colored from the login until they chat.
     */
    private val present = HashMap<String, Map<String, String>>()

    fun remember(channel: String, login: String, displayName: String?, color: Int?) {
        val map = perChannel.getOrPut(channel) { LinkedHashMap(64, 0.75f, true) }
        val key = login.lowercase()
        map[key] = Chatter(displayName ?: login, color)
        if (map.size > MAX_CHATTERS) map.remove(map.keys.first())
    }

    /** Replaces the channel's Twitch chatter list (login to display name). */
    fun setPresent(channel: String, users: Map<String, String>) {
        if (users.isEmpty()) present.remove(channel) else present[channel] = users
    }

    /**
     * The chatter if known in this channel. Someone from Twitch's list who has not written yet has
     * no color.
     */
    fun find(channel: String, login: String): Chatter? {
        val key = login.lowercase()
        return perChannel[channel]?.get(key)
            ?: present[channel]?.get(key)?.let { Chatter(it, null) }
    }

    /** Names for autocomplete: recent chatters first, then everyone else Twitch lists. */
    fun names(channel: String): List<String> {
        val recent = perChannel[channel]?.values?.map { it.displayName }?.reversed().orEmpty()
        val rest = present[channel] ?: return recent
        val seen = perChannel[channel]?.keys.orEmpty()
        return recent + rest.entries.filter { it.key !in seen }.map { it.value }.sorted()
    }

    fun remove(channel: String) {
        perChannel.remove(channel)
        present.remove(channel)
    }

    fun clear() {
        perChannel.clear()
        present.clear()
    }

    private companion object {
        const val MAX_CHATTERS = 500
    }
}
