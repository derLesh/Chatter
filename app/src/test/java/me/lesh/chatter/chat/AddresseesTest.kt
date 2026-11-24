package me.lesh.chatter.chat

import me.lesh.chatter.stats.TalkedTo
import org.junit.Assert.assertEquals
import org.junit.Test

class AddresseesTest {
    private fun sent(vararg segments: Segment, reply: ReplyInfo? = null) = ChatItem(
        id = "own", channel = "forsen", kind = MessageKind.Chat, timestamp = 0, login = "lukas",
        body = MessageBody.of(segments.toList()), reply = reply,
    )

    @Test
    fun `an @mention counts, without the at sign and trailing punctuation`() {
        val message = sent(Segment.Text("hi "), Segment.Mention("@Forsen,"))
        assertEquals(listOf(TalkedTo("forsen", "Forsen")), Addressees.of(message, "lukas"))
    }

    @Test
    fun `the chatter answered counts, first and once`() {
        val reply = ReplyInfo("1", parentLogin = "xqc", parentDisplayName = "xQc", parentBody = "hi")
        val message = sent(Segment.Mention("@xqc"), Segment.Mention("@pokimane"), reply = reply)
        assertEquals(listOf(TalkedTo("xqc", "xQc"), TalkedTo("pokimane", "pokimane")), Addressees.of(message, "lukas"))
    }

    @Test
    fun `the user's own name and words that are no login are left out`() {
        val message = sent(Segment.Mention("@Lukas"), Segment.Mention("@"), Segment.Mention("@a!"))
        assertEquals(emptyList<TalkedTo>(), Addressees.of(message, "lukas"))
    }
}
