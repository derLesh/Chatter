package dev.chatter.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InboxOwnersTest {
    private data class Row(val id: String, val owner: String?)

    private fun keepOnly(rows: List<Row>, vararg accounts: String) =
        InboxOwners.keepOnly(rows, accounts.toList(), { it.owner }) { row, id -> row.copy(owner = id) }

    @Test
    fun theRowsOfAnAccountThatIsGoneGoWithIt() {
        val rows = listOf(Row("1", "a"), Row("2", "b"), Row("3", "a"))
        assertEquals(listOf(Row("2", "b")), keepOnly(rows, "b"))
    }

    /** No change must mean no write; the stores compare by identity. */
    @Test
    fun nothingToChangeHandsTheSameListBack() {
        val rows = listOf(Row("1", "a"), Row("2", "b"))
        assertSame(rows, keepOnly(rows, "a", "b"))
    }

    @Test
    fun rowsFromBeforeOwnersGoToTheOnlyAccount() {
        val rows = listOf(Row("1", null), Row("2", "a"))
        assertEquals(listOf(Row("1", "a"), Row("2", "a")), keepOnly(rows, "a"))
    }

    /**
     * With two accounts nobody can tell whose a whisper was; guessing could show it to the wrong
     * one.
     */
    @Test
    fun rowsFromBeforeOwnersAreDroppedWhenTheAccountIsUnclear() {
        val rows = listOf(Row("1", null), Row("2", "b"))
        assertEquals(listOf(Row("2", "b")), keepOnly(rows, "a", "b"))
    }

    @Test
    fun noAccountLeavesNothing() {
        assertEquals(emptyList<Row>(), keepOnly(listOf(Row("1", null), Row("2", "a"))))
    }
}
