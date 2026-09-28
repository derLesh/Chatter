package dev.chatter.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test
    fun caseAndUmlautsDoNotMatter() {
        assertTrue(SettingsSearch.matches("uber", listOf("Über")))
        assertTrue(SettingsSearch.matches("BENACHRICHT", listOf("Benachrichtigungen")))
        assertTrue(SettingsSearch.matches("grosse", listOf("Textgröße")))
    }

    @Test
    fun everyWordHasToBeThereInAnyOrder() {
        val texts = listOf("Linked images", "A url with a picture is replaced by the picture")
        assertTrue(SettingsSearch.matches("picture link", texts))
        assertFalse(SettingsSearch.matches("picture video", texts))
    }

    @Test
    fun aBlankQueryFindsNothing() {
        assertFalse(SettingsSearch.matches("", listOf("Anything")))
        assertFalse(SettingsSearch.matches("   ", listOf("Anything")))
    }

    @Test
    fun theNumberInATitleIsLeftOut() {
        assertEquals("Text size", SettingsSearch.plainTitle("Text size: %1\$d"))
        assertEquals("Gespeicherte Nachrichten pro Channel", SettingsSearch.plainTitle("Gespeicherte Nachrichten pro Channel: %1\$d"))
        assertEquals("Timestamps", SettingsSearch.plainTitle("Timestamps"))
    }
}
