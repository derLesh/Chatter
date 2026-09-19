package dev.chatter.app.chat

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    @Test
    fun normalMessagesAndMeAreNotCommands() {
        assertNull(CommandParser.parse("hello"))
        assertNull(CommandParser.parse("/me waves"))
    }

    @Test
    fun banAndTimeout() {
        assertEquals(ChatCommand.Ban("troll", "spam bot"), CommandParser.parse("/ban @Troll spam bot"))
        assertEquals(ChatCommand.Timeout("troll", 600, null), CommandParser.parse("/timeout troll"))
        assertEquals(ChatCommand.Timeout("troll", 3600, "calm down"), CommandParser.parse("/timeout troll 1h calm down"))
        assertEquals(ChatCommand.Timeout("troll", 1_209_600, null), CommandParser.parse("/timeout troll 5w"))
        assertEquals(ChatCommand.Unban("troll"), CommandParser.parse("/untimeout troll"))
    }

    @Test
    fun missingOrInvalidArgumentsGiveUsage() {
        assertEquals(ChatCommand.Usage("/ban <user> [reason]"), CommandParser.parse("/ban"))
        assertTrue(CommandParser.parse("/timeout troll soon") is ChatCommand.Usage)
        assertTrue(CommandParser.parse("/slow fast") is ChatCommand.Usage)
    }

    @Test
    fun chatSettings() {
        val slow = CommandParser.parse("/slow 10") as ChatCommand.Settings
        assertTrue(slow.settings["slow_mode"]!!.jsonPrimitive.boolean)
        assertEquals(10, slow.settings["slow_mode_wait_time"]!!.jsonPrimitive.int)

        val followers = CommandParser.parse("/followers 1h") as ChatCommand.Settings
        assertEquals(60, followers.settings["follower_mode_duration"]!!.jsonPrimitive.int)
        val followersPlain = CommandParser.parse("/followers 10") as ChatCommand.Settings
        assertEquals("plain number = minutes", 10, followersPlain.settings["follower_mode_duration"]!!.jsonPrimitive.int)
    }

    @Test
    fun otherCommands() {
        assertEquals(ChatCommand.Clear, CommandParser.parse("/clear"))
        assertEquals(ChatCommand.Mod("someone", false), CommandParser.parse("/unmod someone"))
        assertEquals(ChatCommand.Announce("big news today"), CommandParser.parse("/announce big news today"))
        assertEquals(ChatCommand.Color("blue_violet"), CommandParser.parse("/color BlueViolet"))
        assertEquals(ChatCommand.Unknown("dance"), CommandParser.parse("/dance"))
    }

    @Test
    fun durations() {
        assertEquals(30, CommandParser.parseDuration("30"))
        assertEquals(600, CommandParser.parseDuration("10m"))
        assertEquals(86_400, CommandParser.parseDuration("1d"))
        assertNull(CommandParser.parseDuration("10x"))
        assertNull(CommandParser.parseDuration("-5"))
    }
}
