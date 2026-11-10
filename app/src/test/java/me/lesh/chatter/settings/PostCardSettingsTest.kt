package me.lesh.chatter.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import me.lesh.chatter.chat.PostSite
import me.lesh.chatter.testing.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCardSettingsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    @Test
    fun onlyYouTubeStartsCompact() {
        val fresh = PostCardStore.read(preferencesOf(), PostSite.YouTube)
        assertTrue(fresh.compact)
        assertFalse(PostCardStore.read(preferencesOf(), PostSite.X).compact)
        assertFalse(PostCardStore.read(preferencesOf(), PostSite.Bluesky).compact)
    }

    @Test
    fun whatEverySiteSharedBeforeCarriesOverToEach() {
        val before = preferencesOf(
            booleanPreferencesKey("post_avatar") to false,
            booleanPreferencesKey("post_counts") to false,
            intPreferencesKey("post_text_lines") to 3,
            stringPreferencesKey("post_picture_size") to "Large",
        )
        for (site in PostSite.entries) {
            val card = PostCardStore.read(before, site)
            assertFalse(card.avatar)
            assertFalse(card.counts)
            assertTrue(card.date)
            assertEquals(3, card.textLines)
            assertEquals(PostPictureSize.Large, card.pictureSize)
        }
    }

    @Test
    fun aSitesOwnChoiceWinsOverTheSharedOne() {
        val prefs = mutablePreferencesOf(booleanPreferencesKey("post_avatar") to false)
        prefs[booleanPreferencesKey("post_card_x_avatar")] = true
        assertTrue(PostCardStore.read(prefs, PostSite.X).avatar)
        assertFalse(PostCardStore.read(prefs, PostSite.Bluesky).avatar)
    }

    @Test
    fun changingOneSiteLeavesTheOthersAlone() = runBlocking {
        val store = MemoryStore()
        val settings = SettingsRepository(store, scope)
        settings.updatePostCard(PostSite.X) { it.copy(compact = true, textLines = 2) }
        val stored = store.data.first()
        assertTrue(PostCardStore.read(stored, PostSite.X).compact)
        assertEquals(2, PostCardStore.read(stored, PostSite.X).textLines)
        assertFalse(PostCardStore.read(stored, PostSite.Bluesky).compact)
        assertEquals(PostTextLines.DEFAULT, PostCardStore.read(stored, PostSite.Bluesky).textLines)
    }

    @Test
    fun aBackupWithoutASiteGivesItsDefaults() {
        val partial = Settings(postCards = mapOf(PostSite.X to PostCardSettings(compact = true)))
        assertTrue(partial.postCard(PostSite.X).compact)
        assertTrue(partial.postCard(PostSite.YouTube).compact)
        assertFalse(partial.postCard(PostSite.Bluesky).compact)
    }
}
