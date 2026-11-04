package me.lesh.chatter.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelChangesTest {
    private val changes = ChannelChanges { if (it == "forsen") "Old title" to "Just Chatting" else "" to "" }

    @Test
    fun onlyWhatChangedSinceTheLiveCheckIsNew() {
        assertEquals(StreamChange(title = "New title", category = null), changes.changed("forsen", "New title", "Just Chatting"))
    }

    @Test
    fun aChangeOfLanguageOrLabelsIsLeftOut() {
        assertNull(changes.changed("forsen", "Old title", "Just Chatting"))
    }

    @Test
    fun eachUpdateIsComparedWithTheOneBefore() {
        changes.changed("forsen", "New title", "Just Chatting")
        assertEquals(StreamChange(title = null, category = "Minecraft"), changes.changed("forsen", "New title", "Minecraft"))
    }

    @Test
    fun anOfflineChannelSaysBoth() {
        assertEquals(StreamChange("Title", "Art"), changes.changed("xqc", "Title", "Art"))
    }
}
