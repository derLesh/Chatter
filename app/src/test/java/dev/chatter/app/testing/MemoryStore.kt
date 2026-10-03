package dev.chatter.app.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory preferences store for repository tests. Like the real one it applies one change at a
 * time on the previous result. The real file store cannot replace its file on Windows.
 */
class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val lock = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}
