package dev.chatter.app.chat

/**
 * The users seen chatting recently, per channel. Feeds the name autocomplete and lets
 * [MessageBuilder] color an "@name" in the mentioned user's own chat color.
 *
 * Only touched from [ChatRepository]'s single worker dispatcher, so no locks are needed.
 */
class ChatterRegistry {
    /** Display name and chat color of one chatter; the color is null when they never picked one. */
    data class Chatter(val displayName: String, val color: Int?)

    private val perChannel = HashMap<String, LinkedHashMap<String, Chatter>>()

    fun remember(channel: String, login: String, displayName: String?, color: Int?) {
        val map = perChannel.getOrPut(channel) { LinkedHashMap(64, 0.75f, true) }
        val key = login.lowercase()
        map[key] = Chatter(displayName ?: login, color)
        if (map.size > MAX_CHATTERS) map.remove(map.keys.first())
    }

    /** The chatter if they have written in this channel recently, else null. */
    fun find(channel: String, login: String): Chatter? = perChannel[channel]?.get(login.lowercase())

    /** Display names of recently active chatters, most recent first. */
    fun names(channel: String): List<String> =
        perChannel[channel]?.values?.map { it.displayName }?.reversed().orEmpty()

    fun remove(channel: String) {
        perChannel.remove(channel)
    }

    fun clear() {
        perChannel.clear()
    }

    private companion object {
        const val MAX_CHATTERS = 500
    }
}
