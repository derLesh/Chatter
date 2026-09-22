package dev.chatter.app.settings

import dev.chatter.app.channels.ChannelGroup
import dev.chatter.app.chat.ChatRule
import dev.chatter.app.chat.RuleAction
import dev.chatter.app.emotes.EmoteProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val json = SettingsBackup.json

    @Test
    fun everythingSurvivesTheRoundTrip() {
        val backup = SettingsBackup(
            createdAt = 1_700_000_000_000,
            settings = Settings(
                fontSize = 18f,
                timestamps = TimestampFormat.Seconds,
                muteKeywords = listOf("bot", "spam"),
                emoteProviders = setOf(EmoteProvider.SevenTv),
            ),
            rules = listOf(ChatRule("r1", "giveaway", action = RuleAction.Notify, color = 0xFF2196F3.toInt())),
            nicknames = mapOf("forsen" to "The Man"),
            channels = ChannelBackup(
                logins = listOf("forsen", "+a1b2c3d4", "xqc"),
                names = mapOf("xqc" to "X"),
                groups = listOf(ChannelGroup("a1b2c3d4", "Both", listOf("forsen", "xqc"))),
            ),
        )

        val restored = json.decodeFromString<SettingsBackup>(json.encodeToString(backup))
        assertEquals(backup, restored)
    }

    @Test
    fun partsThatAreMissingStayNull() {
        val restored = json.decodeFromString<SettingsBackup>("""{"app":"Chatter","version":1}""")
        assertNull(restored.settings)
        assertNull(restored.rules)
        assertNull(restored.channels)
    }

    @Test
    fun aBackupFromBeforeCombinedChatsHasNone() {
        val restored = json.decodeFromString<SettingsBackup>(
            """{"app":"Chatter","version":1,"channels":{"logins":["forsen"]}}"""
        )
        assertEquals(emptyList<ChannelGroup>(), restored.channels?.groups)
    }

    @Test
    fun unknownFieldsFromANewerVersionAreIgnored() {
        val restored = json.decodeFromString<SettingsBackup>(
            """{"app":"Chatter","version":99,"whatIsThis":true,"nicknames":{"forsen":"Sen"}}"""
        )
        assertEquals(mapOf("forsen" to "Sen"), restored.nicknames)
    }

    @Test
    fun anExportCarriesTheAppNameSoAForeignFileCanBeTurnedDown() {
        val text = json.encodeToString(SettingsBackup())
        assertTrue(text.contains("\"app\": \"Chatter\""))
    }
}
