package me.lesh.chatter.emotes

/** The channel a Twitch emote comes from, as the picker shows it. */
data class EmoteOwner(val id: String, val name: String, val avatarUrl: String?, val subscribed: Boolean = false)

/** One run of Twitch emotes in the picker. */
sealed interface TwitchEmoteGroup {
    val emotes: List<Emote>

    class Channel(val owner: EmoteOwner, override val emotes: List<Emote>) : TwitchEmoteGroup

    /** Channel emotes whose channel name did not load. */
    class OtherChannels(override val emotes: List<Emote>) : TwitchEmoteGroup

    /** Twitch's own: globals, smilies, Prime, Turbo. */
    class Twitch(override val emotes: List<Emote>) : TwitchEmoteGroup
}

/**
 * Twitch emotes grouped the way the picker lists them: the channels the user is subscribed to, then
 * the other channels the user has emotes of (follower, bits, events), each by name; then those of
 * unknown channels, then Twitch's own last. Empty groups are left out.
 */
fun groupTwitchEmotes(emotes: List<Emote>, owners: Map<String, EmoteOwner>): List<TwitchEmoteGroup> {
    val byName = compareBy<Emote> { it.name.lowercase() }
    val (channel, twitch) = emotes.partition { it.ownerId != null }
    val (known, unknown) = channel.partition { it.ownerId in owners }
    val channels = known.groupBy { it.ownerId!! }
        .map { (id, list) -> TwitchEmoteGroup.Channel(owners.getValue(id), list.sortedWith(byName)) }
        .sortedWith(compareByDescending<TwitchEmoteGroup.Channel> { it.owner.subscribed }.thenBy { it.owner.name.lowercase() })
    return channels +
        listOfNotNull(
            unknown.takeIf { it.isNotEmpty() }?.let { TwitchEmoteGroup.OtherChannels(it.sortedWith(byName)) },
            twitch.takeIf { it.isNotEmpty() }?.let { TwitchEmoteGroup.Twitch(it.sortedWith(byName)) },
        )
}

/** Helix names Twitch's own emotes with owner "0" or "twitch". */
internal fun twitchOwnerId(ownerId: String): String? = ownerId.takeUnless { it.isEmpty() || it == "0" || it == "twitch" }
