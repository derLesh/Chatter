package me.lesh.chatter.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentlySentTest {
    private fun own(text: String, kind: MessageKind = MessageKind.Chat) =
        ChatItem(id = text, channel = "forsen", kind = kind, timestamp = 0, login = "lukas", text = text)

    @Test
    fun newestComesFirstAndOnlyAsManyAsAsked() {
        val sent = listOf(own("one"), own("two"), own("three"), own("four"))
        assertEquals(listOf("four", "three", "two"), RecentlySent.texts(sent, limit = 3))
    }

    @Test
    fun aRepeatedLineIsOfferedOnce() {
        // The second copy went out with the tag Twitch needs to let a line repeat.
        val sent = listOf(own("hello"), own("Pog"), own("Pog 󠀀"))
        assertEquals(listOf("Pog", "hello"), RecentlySent.texts(sent, limit = 3))
    }

    @Test
    fun aLineChatterRepeatedIsOfferedOnce() {
        // From the history: Twitch kept what Chatter added to let the copies through.
        val sent = listOf(own("gg wp"), own("gg  wp"), own("Pog"), own("Pog ͏"))
        assertEquals(listOf("Pog", "gg wp"), RecentlySent.texts(sent, limit = 3))
    }

    @Test
    fun anActionComesBackAsMe() {
        assertEquals(listOf("/me waves"), RecentlySent.texts(listOf(own("waves", MessageKind.Action)), limit = 3))
    }

    @Test
    fun noticesAreNotMessagesTheUserWrote() {
        val sent = listOf(own("hi"), own("lukas subscribed", MessageKind.UserNotice))
        assertEquals(listOf("hi"), RecentlySent.texts(sent, limit = 3))
    }
}
