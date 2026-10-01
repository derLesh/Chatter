package dev.chatter.app.channels

import dev.chatter.app.irc.IrcConnection
import dev.chatter.app.settings.BackupCheck
import dev.chatter.app.settings.ChannelBackup
import dev.chatter.app.settings.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names that end up in the comma-separated page list, and from there in IRC commands. */
class ChannelNamesTest {
    @Test
    fun theIdsTheAppMakesAreValid() {
        assertTrue(ChannelGroup.isValidId("a1b2c3d4"))
        assertTrue(ChannelGroup.isValidId("123e4567-e89b-12d3-a456-426614174000"))
    }

    /** A comma splits the page list, a line break the IRC line. */
    @Test
    fun idsThatWouldBreakTheListOrTheCommandAreNot() {
        listOf("", "x,forsen", "a\r\nPRIVMSG #z :hi", "a b", "+a", "a".repeat(37)).forEach {
            assertFalse(it, ChannelGroup.isValidId(it))
        }
    }

    @Test
    fun aBackupLosesTheCombinedChatsWithABadIdAndTheirPlace() {
        val good = ChannelGroup("a1b2c3d4", "Both", listOf("forsen", "xqc"))
        val bad = ChannelGroup("x,y\r\nPRIVMSG #z :hi", "", listOf("forsen"))
        val backup = SettingsBackup(
            channels = ChannelBackup(logins = listOf("forsen", good.key, bad.key, "xqc"), groups = listOf(good, bad)),
        )
        val cleaned = BackupCheck.clean(backup).channels!!
        assertEquals(listOf(good), cleaned.groups)
        assertEquals(listOf("forsen", good.key, "xqc"), cleaned.logins)
    }

    @Test
    fun onlyTwitchLoginsReachAnIrcCommand() {
        assertTrue(IrcConnection.isChannel("forsen"))
        assertTrue(IrcConnection.isChannel("a_b_1"))
        listOf("", "y\r\nPRIVMSG #z :hi", "a,b", "Forsen", "#forsen", "a b", "+a1b2c3d4", "a".repeat(26)).forEach {
            assertFalse(it, IrcConnection.isChannel(it))
        }
    }
}
