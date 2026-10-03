package dev.chatter.app.settings

import dev.chatter.app.chat.ChatRule
import org.junit.Assert.assertEquals
import org.junit.Test

/** A backup somebody else made, held to what the settings screen would allow. */
class BackupCheckTest {
    private fun clean(settings: Settings) = BackupCheck.clean(SettingsBackup(settings = settings)).settings!!

    /** "com" would match every .com site, and every picture linked from one would be fetched. */
    @Test
    fun hostsThatMatchWholeDomainsAreDropped() {
        val hosts = clean(Settings(imageHosts = listOf("com", "", ".", "imgur.com", "*.example.com", "a..b"))).imageHosts
        assertEquals(listOf("imgur.com"), hosts)
    }

    @Test
    fun hostsAreCleanedTheWayTheSettingsScreenCleansThem() {
        val hosts = clean(Settings(imageHosts = listOf("https://www.Gyazo.com/abc", "user@i.nuuls.com:443", "gyazo.com"))).imageHosts
        assertEquals(listOf("gyazo.com", "i.nuuls.com"), hosts)
    }

    @Test
    fun numbersAreBroughtIntoTheirRange() {
        val s = clean(Settings(fontSize = 400f, messageLimit = 2_000_000))
        assertEquals(24f, s.fontSize)
        assertEquals(2000, s.messageLimit)
        assertEquals(10f, clean(Settings(fontSize = -1f)).fontSize)
        assertEquals(Settings().fontSize, clean(Settings(fontSize = Float.NaN)).fontSize)
    }

    /** They are stored comma-separated: a comma inside one would make two of it. */
    @Test
    fun keywordsLoseTheirCommasAndBlanks() {
        val s = clean(Settings(mentionKeywords = listOf("a,b", " ", "c", "c")))
        assertEquals(listOf("ab", "c"), s.mentionKeywords)
    }

    @Test
    fun listsAreCapped() {
        val s = clean(Settings(muteKeywords = List(1000) { "w$it" }))
        assertEquals(BackupCheck.MAX_KEYWORDS, s.muteKeywords.size)
        val rules = BackupCheck.clean(SettingsBackup(rules = List(1000) { ChatRule("r$it", "p$it") })).rules!!
        assertEquals(BackupCheck.MAX_RULES, rules.size)
    }

    @Test
    fun emptyAndOverlongRulesAreDropped() {
        val rules = listOf(ChatRule("1", " "), ChatRule("2", "x".repeat(BackupCheck.MAX_PATTERN + 1)), ChatRule("3", "ok"))
        assertEquals(listOf("3"), BackupCheck.clean(SettingsBackup(rules = rules)).rules!!.map { it.id })
    }

    @Test
    fun nicknamesNeedARealLogin() {
        val nicknames = mapOf("Forsen" to " The Man ", "not a login!" to "x", "xqc" to "")
        assertEquals(mapOf("forsen" to "The Man"), BackupCheck.clean(SettingsBackup(nicknames = nicknames)).nicknames)
    }

    @Test
    fun aWellBehavedBackupComesThroughUnchanged() {
        val backup = SettingsBackup(settings = Settings(), rules = listOf(ChatRule("1", "giveaway")), nicknames = mapOf("forsen" to "F"))
        assertEquals(backup, BackupCheck.clean(backup))
    }

    @Test
    fun aFileTooLargeToBeABackupIsNotReadIntoMemory() {
        val huge = java.io.ByteArrayInputStream(ByteArray(BackupCheck.MAX_FILE_BYTES + 1) { 'x'.code.toByte() })
        assertEquals(null, BackupCheck.readLimited(huge))
        val small = java.io.ByteArrayInputStream("{\"app\":\"Chatter\"}".toByteArray())
        assertEquals("{\"app\":\"Chatter\"}", BackupCheck.readLimited(small))
    }
}
