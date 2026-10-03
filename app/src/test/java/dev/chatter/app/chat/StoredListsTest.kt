package dev.chatter.app.chat

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.testing.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Repositories must leave stored lists they cannot read untouched. */
class StoredListsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    /** What a version writing a different shape might leave behind. */
    private val fromTheFuture = """{"version":2,"rules":[]}"""

    private val rulesKey = stringPreferencesKey("chat_rules")
    private val mentionsKey = stringPreferencesKey("inbox_mentions")

    @Test
    fun aRuleSavedOverUnreadableRulesDoesNotWipeThem() = runBlocking {
        val store = MemoryStore(mutablePreferencesOf(rulesKey to fromTheFuture))
        val rules = RuleRepository(store, scope)
        rules.save(ChatRule(id = "1", pattern = "hi"))
        assertEquals(fromTheFuture, store.data.first()[rulesKey])
    }

    @Test
    fun readableRulesAreChangedAsUsual() = runBlocking {
        val store = MemoryStore()
        val rules = RuleRepository(store, scope)
        rules.save(ChatRule(id = "1", pattern = "hi"))
        rules.save(ChatRule(id = "2", pattern = "ho"))
        rules.delete("1")
        assertEquals(listOf("2"), rules.rules.first { it.size == 1 }.map { it.id })
    }

    @Test
    fun aNewMentionDoesNotWriteOverAnInboxThatCannotBeRead() = runBlocking {
        val store = MemoryStore(mutablePreferencesOf(mentionsKey to fromTheFuture))
        val inbox = MentionInboxRepository(store, MutableStateFlow("1"), scope)
        val item = ChatItem(id = "m", channel = "forsen", kind = MessageKind.Chat, timestamp = 0, login = "a", text = "@lukas")
        inbox.add(item, read = false, account = "1")
        assertEquals(fromTheFuture, store.data.first()[mentionsKey])
    }
}
