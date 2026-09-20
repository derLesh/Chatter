package dev.chatter.app.chat

/**
 * The users known in a channel: those seen chatting recently, plus the chatter list Twitch
 * reports for channels the user moderates. Feeds the name autocomplete and lets [MessageBuilder]
 * color an "@name" in the mentioned user's own chat color.
 *
 * Only touched from [ChatRepository]'s single worker dispatcher, so no locks are needed.
 */
class ChatterRegistry {
    /** Display name and chat color of one chatter; the color is null when they never picked one. */
    data class Chatter(val displayName: String, val color: Int?)

    private val perChannel = HashMap<String, LinkedHashMap<String, Chatter>>()

    /**
     * Everyone Twitch lists as present, by lowercase login. These have no color yet (Twitch does
     * not report one), so a mention of them is colored from their login until they chat.
     */
    private val present = HashMap<String, Map<String, String>>()

    fun remember(channel: String, login: String, displayName: String?, color: Int?) {
        val map = perChannel.getOrPut(channel) { LinkedHashMap(64, 0.75f, true) }
        val key = login.lowercase()
        map[key] = Chatter(displayName ?: login, color)
        if (map.size > MAX_CHATTERS) map.remove(map.keys.first())
    }

    /** Replaces the channel's Twitch chatter list ([login] to display name). */
    fun setPresent(channel: String, users: Map<String, String>) {
        if (users.isEmpty()) present.remove(channel) else present[channel] = users
    }

    /**
     * The chatter if they are known in this channel, else null. Someone from the Twitch list who
     * has not written yet is known by name but has no color of their own.
     */
    fun find(channel: String, login: String): Chatter? {
        val key = login.lowercase()
        return perChannel[channel]?.get(key)
            ?: present[channel]?.get(key)?.let { Chatter(it, null) }
    }

    /**
     * Names for the autocomplete: recently active chatters first (they are who one usually wants
     * to reply to), then everyone else Twitch lists as present.
     */
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
