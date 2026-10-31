package me.lesh.chatter.badges

/**
 * Which 7TV cosmetic each Twitch user id wears, for the [max] users seen most recently. 7TV sends
 * this for everyone in the joined channels, so over a night in busy chats an unbounded map would
 * only grow; a lookup counts as seen, so whoever is chatting stays in.
 */
internal class RecentWearers(private val max: Int) {
    private val wearers = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > max
    }

    operator fun get(userId: String): String? = synchronized(wearers) { wearers[userId] }

    operator fun set(userId: String, cosmeticId: String) {
        synchronized(wearers) { wearers[userId] = cosmeticId }
    }

    /** Takes [userId] off only if they still wear [cosmeticId]; a newer one stays. */
    fun remove(userId: String, cosmeticId: String) {
        synchronized(wearers) { if (wearers[userId] == cosmeticId) wearers.remove(userId) }
    }

    val size: Int get() = synchronized(wearers) { wearers.size }
}
