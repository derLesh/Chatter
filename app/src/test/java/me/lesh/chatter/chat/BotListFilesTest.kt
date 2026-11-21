package me.lesh.chatter.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The bots' command lists on the phone: bounded, and gone with their channel. */
class BotListFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aRemovedChannelTakesTheListsOfEveryBotAlong() {
        val files = BotListFiles(folder.root)
        files.write("Fossabot/forsen", "a")
        files.write("PotatBot/forsen", "b")
        files.write("Fossabot/xqc", "c")
        files.write("PotatBot", "shared")
        files.deleteChannel("forsen")
        assertNull(files.read("Fossabot/forsen"))
        assertNull(files.read("PotatBot/forsen"))
        assertEquals("c", files.read("Fossabot/xqc"))
        assertEquals("shared", files.read("PotatBot"))
    }

    @Test
    fun theOldestGoOnceAllTogetherAreTooBig() {
        val files = BotListFiles(folder.root, maxBytes = 25)
        files.write("Fossabot/a", "x".repeat(10))
        folder.root.resolve("Fossabot/a.json").setLastModified(1_000)
        files.write("Fossabot/b", "x".repeat(10))
        folder.root.resolve("Fossabot/b.json").setLastModified(2_000)
        files.write("Fossabot/c", "x".repeat(10))
        assertNull(files.read("Fossabot/a"))
        assertEquals(20L, files.size())
    }
}
