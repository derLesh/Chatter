package me.lesh.chatter.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetChangesTest {
    @Test
    fun arrivalsKeepAllScopesUntilTheWorkerCanReadThem() {
        val changes = AssetChanges()
        changes.changed("a")
        changes.changed("b")
        changes.changed("a")
        assertEquals(mapOf("a" to 2L, "b" to 1L), changes.arrived.value)
        assertEquals(0L, changes.revision("c"))
        changes.changed()
        assertEquals(3L, changes.revision("a"))
        assertEquals(1L, changes.revision("c"))
    }
}
