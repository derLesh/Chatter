package dev.chatter.app.settings

import dev.chatter.app.channels.ChannelRepository
import dev.chatter.app.channels.LiveAlertChoices
import dev.chatter.app.chat.NicknameRepository
import dev.chatter.app.chat.RuleRepository
import dev.chatter.app.net.HelixApi
import dev.chatter.app.testing.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A backup written on one phone and restored on a fresh install, through the real repositories. */
class BackupRoundTripTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One install: its own store, and a Twitch API that is never logged in. */
    private inner class Phone {
        private val store = MemoryStore()
        val channels = ChannelRepository(store, HelixApi(OkHttpClient()) { null }, scope)
        val backups = BackupManager(
            SettingsRepository(store, scope),
            RuleRepository(store, scope),
            NicknameRepository(store, scope),
            channels,
        )

        /** The choices once something is stored; a missing choice fails instead of waiting forever. */
        suspend fun liveAlerts(): LiveAlertChoices =
            withTimeout(5_000) { channels.liveAlerts.first { it != LiveAlertChoices() } }
    }

    @After
    fun stop() = scope.cancel()

    @Test
    fun liveAlertChoicesMoveToANewPhone() = runBlocking {
        val old = Phone()
        old.channels.add("forsen")
        // Only followed, not in the list.
        old.channels.setLiveAlert("xqc", enabled = true)
        old.channels.setLiveAlert("forsen", enabled = false)
        val expected = old.liveAlerts()

        val new = Phone()
        new.backups.apply(new.backups.read(old.backups.export())!!)

        assertEquals(LiveAlertChoices(on = setOf("xqc"), off = setOf("forsen")), expected)
        assertEquals(expected, new.liveAlerts())
    }

    @Test
    fun aBackupFromBeforeLiveAlertsKeepsThePhonesOwn() = runBlocking {
        val phone = Phone()
        phone.channels.setLiveAlert("xqc", enabled = true)
        val before = phone.liveAlerts()

        phone.backups.apply(phone.backups.read("""{"app":"Chatter","channels":{"logins":["forsen"]}}""")!!)

        assertEquals(before, phone.channels.liveAlerts.value)
    }

    @Test
    fun liveAlertsForNamesThatAreNoLoginsAreDropped() = runBlocking {
        val phone = Phone()
        val text = """{"app":"Chatter","channels":{"liveAlertsOn":["XQC","x,y","a b"],"liveAlertsOff":["#nope"]}}"""

        phone.backups.apply(phone.backups.read(text)!!)

        assertEquals(LiveAlertChoices(on = setOf("xqc")), phone.liveAlerts())
    }
}
