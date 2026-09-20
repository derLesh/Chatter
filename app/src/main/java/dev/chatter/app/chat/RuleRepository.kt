package dev.chatter.app.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.net.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The user's [ChatRule]s, in the order they are applied. */
class RuleRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    val rules: StateFlow<List<ChatRule>> = store.data
        .map { p -> decode(p[RULES]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Adds a rule, or replaces the one with the same id. */
    suspend fun save(rule: ChatRule) = update { list ->
        if (list.any { it.id == rule.id }) list.map { if (it.id == rule.id) rule else it }
        else list + rule
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = update { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /** Replaces the whole set, for importing a backup. */
    suspend fun replaceAll(rules: List<ChatRule>) = update { rules }

    private suspend fun update(transform: (List<ChatRule>) -> List<ChatRule>) {
        store.edit { p -> p[RULES] = AppJson.encodeToString(transform(decode(p[RULES]))) }
    }

    private companion object {
        val RULES = stringPreferencesKey("chat_rules")

        fun decode(raw: String?): List<ChatRule> =
            raw?.let { runCatching { AppJson.decodeFromString<List<ChatRule>>(it) }.getOrNull() }.orEmpty()
    }
}
