package me.lesh.chatter.emotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the picker groups Twitch emotes by the channel they come from. */
class TwitchEmoteGroupsTest {
    private fun emote(name: String, owner: String? = null) =
        Emote(name, name, twitchEmoteUrl(name), EmoteProvider.Twitch, isChannel = owner != null, ownerId = owner)

    private val owners = mapOf(
        "1" to EmoteOwner("1", "zackrawrr", null),
        "2" to EmoteOwner("2", "Forsen", null),
    )

    @Test
    fun channelsComeByNameAndTwitchsOwnLast() {
        val groups = groupTwitchEmotes(
            listOf(emote("Kappa"), emote("zackHi", "1"), emote("forsenE", "2"), emote("LUL")),
            owners,
        )
        assertEquals(listOf("Forsen", "zackrawrr"), groups.filterIsInstance<TwitchEmoteGroup.Channel>().map { it.owner.name })
        val twitch = groups.last()
        assertTrue(twitch is TwitchEmoteGroup.Twitch)
        assertEquals(listOf("Kappa", "LUL"), twitch.emotes.map { it.name })
    }

    @Test
    fun subscriptionsComeBeforeTheOtherChannels() {
        val withSub = owners + ("3" to EmoteOwner("3", "Lesh", null, subscribed = true))
        val groups = groupTwitchEmotes(listOf(emote("forsenE", "2"), emote("leshHi", "3"), emote("zackHi", "1")), withSub)
        assertEquals(listOf("Lesh", "Forsen", "zackrawrr"), groups.map { (it as TwitchEmoteGroup.Channel).owner.name })
    }

    @Test
    fun emotesOfAChannelWhoseNameDidNotLoadGoBeforeTwitchsOwn() {
        val groups = groupTwitchEmotes(listOf(emote("Kappa"), emote("xqcL", "9"), emote("forsenE", "2")), owners)
        assertEquals(3, groups.size)
        assertTrue(groups[1] is TwitchEmoteGroup.OtherChannels)
        assertEquals(listOf("xqcL"), groups[1].emotes.map { it.name })
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        assertEquals(emptyList<TwitchEmoteGroup>(), groupTwitchEmotes(emptyList(), owners))
        val onlyTwitch = groupTwitchEmotes(listOf(emote("Kappa")), owners)
        assertEquals(1, onlyTwitch.size)
        assertTrue(onlyTwitch.single() is TwitchEmoteGroup.Twitch)
    }

    @Test
    fun twitchsOwnHaveOwnerZeroOrNone() {
        assertNull(twitchOwnerId("0"))
        assertNull(twitchOwnerId(""))
        assertNull(twitchOwnerId("twitch"))
        assertEquals("12826", twitchOwnerId("12826"))
    }
}
