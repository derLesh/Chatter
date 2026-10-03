package dev.chatter.app.settings

import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chatter.app.badges.BadgeProvider
import dev.chatter.app.chat.ImageLinks
import dev.chatter.app.testing.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Quick successive changes to list settings. */
class SettingsListsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = MemoryStore()
    private val settings = SettingsRepository(store, scope)

    @After
    fun stop() = scope.cancel()

    /** What is stored under [key], not what the flow has caught up with. */
    private suspend fun stored(key: String) = store.data.first()[stringPreferencesKey(key)]

    @Test
    fun keywordsChangedTogetherAllTakeEffect() = runBlocking {
        settings.updateMentionKeywords { listOf("a", "b", "c", "d") }
        // Each starts from the stored value, not from what the screen last saw.
        listOf("a", "b", "c").map { word -> async { settings.updateMentionKeywords { it - word } } }.awaitAll()
        assertEquals("d", stored("mention_keywords"))
    }

    @Test
    fun providersSwitchedTogetherAllStaySwitched() = runBlocking {
        BadgeProvider.entries.map { p -> async { settings.updateBadgeProviders { it - p } } }.awaitAll()
        assertEquals("", stored("badge_providers"))
    }

    @Test
    fun theImageHostsStartFromTheDefaultsUntilTheUserChangesThem() = runBlocking {
        val first = ImageLinks.DEFAULT_HOSTS.first()
        settings.updateImageHosts { it - first }
        assertEquals((ImageLinks.DEFAULT_HOSTS - first).joinToString(","), stored("image_hosts"))
    }
}
