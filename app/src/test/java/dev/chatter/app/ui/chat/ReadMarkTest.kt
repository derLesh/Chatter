package dev.chatter.app.ui.chat

import dev.chatter.app.chat.ChatItem
import dev.chatter.app.chat.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the line above the first unseen message goes, whatever happened to the list meanwhile. */
class ReadMarkTest {
    private fun at(id: String, timestamp: Long) =
        ChatItem(id = id, channel = "forsen", kind = MessageKind.Chat, timestamp = timestamp)

    private val list = listOf(at("a", 100), at("b", 200), at("c", 300), at("d", 400))

    @Test
    fun whatCameAfterTheLastMessageSeenIsNew() {
        assertEquals(Unseen(first = 2, count = 2, cutOff = false), ReadMark("b", 200).unseenIn(list))
    }

    @Test
    fun nothingIsNewWhenTheLastMessageSeenIsStillTheNewest() {
        assertNull(ReadMark("d", 400).unseenIn(list))
        assertNull(ReadMark("d", 400).unseenIn(emptyList()))
    }

    @Test
    fun aHiddenLastMessageIsFoundAgainByItsTime() {
        // "b" was deleted by a moderator and is no longer shown.
        assertEquals(Unseen(first = 1, count = 3, cutOff = false), ReadMark("gone", 150).unseenIn(list))
    }

    @Test
    fun moreThanTheListHoldsStartsAtTheTopAndSaysSo() {
        assertEquals(Unseen(first = 0, count = 4, cutOff = true), ReadMark("trimmed", 50).unseenIn(list))
    }
}
