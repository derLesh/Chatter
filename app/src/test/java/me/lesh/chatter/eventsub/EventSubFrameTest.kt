package me.lesh.chatter.eventsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventSubFrameTest {
    private fun notification(type: String, event: String) = EventSubFrame.parse(
        """
        {"metadata":{"message_id":"m1","message_type":"notification","message_timestamp":"2026-11-03T20:00:00Z",
          "subscription_type":"$type","subscription_version":"2"},
         "payload":{"subscription":{"id":"s1","type":"$type"},"event":$event}}
        """
    ) as EventSubFrame.Notification

    @Test
    fun welcomeNamesTheSessionAndItsKeepalive() {
        val frame = EventSubFrame.parse(
            """
            {"metadata":{"message_id":"w","message_type":"session_welcome","message_timestamp":"2026-11-03T20:00:00Z"},
             "payload":{"session":{"id":"AQoQexAWVYKSTIu4ec_2VAxyuhAB","status":"connected","keepalive_timeout_seconds":300,"reconnect_url":null}}}
            """
        )
        assertEquals(EventSubFrame.Welcome("AQoQexAWVYKSTIu4ec_2VAxyuhAB", 300), frame)
    }

    @Test
    fun keepaliveReconnectAndRevocation() {
        assertEquals(
            EventSubFrame.Keepalive,
            EventSubFrame.parse("""{"metadata":{"message_id":"k","message_type":"session_keepalive"},"payload":{}}"""),
        )
        assertEquals(
            EventSubFrame.Reconnect("wss://eventsub.wss.twitch.tv/ws?id=abc"),
            EventSubFrame.parse(
                """{"metadata":{"message_id":"r","message_type":"session_reconnect"},
                   "payload":{"session":{"id":"x","status":"reconnecting","reconnect_url":"wss://eventsub.wss.twitch.tv/ws?id=abc"}}}"""
            ),
        )
        assertEquals(
            EventSubFrame.Revocation("sub-9"),
            EventSubFrame.parse(
                """{"metadata":{"message_id":"v","message_type":"revocation","subscription_type":"automod.message.hold"},
                   "payload":{"subscription":{"id":"sub-9","status":"authorization_revoked"}}}"""
            ),
        )
    }

    @Test
    fun brokenOrUnknownFramesAreLeftOut() {
        assertNull(EventSubFrame.parse("not json"))
        assertNull(EventSubFrame.parse("""{"metadata":{"message_type":"something_new"},"payload":{}}"""))
        assertNull(EventSubFrame.parse("""{"payload":{}}"""))
    }

    @Test
    fun anUnreadTypeStillCountsAsANotification() {
        val frame = notification("channel.cheer", """{"broadcaster_user_id":"1"}""")
        assertEquals("m1", frame.messageId)
        assertNull(frame.event)
    }

    @Test
    fun autoModHoldCarriesTheMessageAndWhy() {
        val event = notification(
            "automod.message.hold",
            """
            {"broadcaster_user_id":"1337","broadcaster_user_login":"streamer","broadcaster_user_name":"Streamer",
             "user_id":"42","user_login":"viewer","user_name":"Viewer","message_id":"held-1",
             "message":{"text":"you are bad Kappa","fragments":[
               {"type":"text","text":"you are bad ","cheermote":null,"emote":null},
               {"type":"emote","text":"Kappa","cheermote":null,"emote":{"id":"25","emote_set_id":"0"}}]},
             "reason":"automod","automod":{"category":"aggressive","level":2,"boundaries":[]},"blocked_term":null,
             "held_at":"2026-11-03T20:00:00Z"}
            """,
        ).event as EventSubEvent.AutoModHeld
        assertEquals("1337", event.channelId)
        assertEquals("held-1", event.messageId)
        assertEquals("viewer", event.login)
        assertEquals("Viewer", event.displayName)
        assertEquals("you are bad Kappa", event.text)
        assertEquals("25", event.fragments[1].emote?.id)
        assertEquals(HoldReason.AutoMod("aggressive", 2), event.reason)
        assertEquals(1_793_736_000_000L, event.heldAt)
    }

    @Test
    fun aBlockedTermIsItsOwnReason() {
        val event = notification(
            "automod.message.hold",
            """
            {"broadcaster_user_id":"1","user_id":"2","user_login":"viewer","user_name":"","message_id":"h",
             "message":{"text":"badword","fragments":[]},"reason":"blocked_term","automod":null,
             "blocked_term":{"terms_found":[]},"held_at":"2026-11-03T20:00:00Z"}
            """,
        ).event as EventSubEvent.AutoModHeld
        assertEquals(HoldReason.BlockedTerm, event.reason)
        // Without a display name the login stands in.
        assertEquals("viewer", event.displayName)
    }

    @Test
    fun autoModUpdateSaysWhoDecided() {
        val event = notification(
            "automod.message.update",
            """
            {"broadcaster_user_id":"1","user_id":"2","user_login":"viewer","message_id":"h",
             "moderator_user_id":"3","moderator_user_login":"mod","moderator_user_name":"Mod","status":"denied"}
            """,
        ).event
        assertEquals(EventSubEvent.AutoModResolved("1", "h", HoldStatus.Denied, "Mod"), event)
    }
}
