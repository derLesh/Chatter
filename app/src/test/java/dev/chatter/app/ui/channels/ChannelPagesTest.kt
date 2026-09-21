package dev.chatter.app.ui.channels

import dev.chatter.app.ui.channels.ChannelPages.Companion.ORIGIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPagesTest {
    private val plain = ChannelPages(channels = 4, carousel = false)
    private val carousel = ChannelPages(channels = 4, carousel = true)

    @Test
    fun withoutTheCarouselAPageIsAChannel() {
        assertEquals(4, plain.count)
        assertEquals(0, plain.channelAt(0))
        assertEquals(3, plain.channelAt(3))
        assertEquals(2, plain.pageOf(2))
    }

    @Test
    fun withoutTheCarouselThereIsNothingPastTheEnds() {
        assertNull(plain.channelAt(4))
        assertNull(plain.channelAt(-1))
    }

    @Test
    fun theCarouselStartsInTheMiddleWithRoomToWrapBothWays() {
        assertEquals(ORIGIN * 2, carousel.count)
        assertEquals(0, carousel.channelAt(ORIGIN))
        assertEquals(ORIGIN, carousel.pageOf(0))
    }

    @Test
    fun pastTheLastChannelComesTheFirst() {
        assertEquals(3, carousel.channelAt(ORIGIN + 3))
        assertEquals(0, carousel.channelAt(ORIGIN + 4))
        assertEquals(1, carousel.channelAt(ORIGIN + 5))
    }

    @Test
    fun beforeTheFirstChannelComesTheLast() {
        assertEquals(3, carousel.channelAt(ORIGIN - 1))
        assertEquals(2, carousel.channelAt(ORIGIN - 2))
    }

    @Test
    fun pickingAChannelTakesTheShortWayRound() {
        // From channel 0, the last of four is one swipe back, not three forward.
        assertEquals(ORIGIN - 1, carousel.pageOf(3, from = ORIGIN))
        assertEquals(ORIGIN + 1, carousel.pageOf(1, from = ORIGIN))
        // And from wherever the user happens to be, not from the origin.
        assertEquals(ORIGIN + 7, carousel.pageOf(3, from = ORIGIN + 6))
    }

    @Test
    fun pickingTheChannelAlreadyOnScreenStaysPut() {
        assertEquals(ORIGIN + 6, carousel.pageOf(2, from = ORIGIN + 6))
    }

    @Test
    fun aChannelThatIsNotThereLeavesThePagerWhereItIs() {
        assertEquals(ORIGIN + 6, carousel.pageOf(9, from = ORIGIN + 6))
        assertEquals(ORIGIN + 6, carousel.pageOf(-1, from = ORIGIN + 6))
    }

    @Test
    fun oneChannelHasNothingToWrapAroundTo() {
        val single = ChannelPages(channels = 1, carousel = true)
        assertFalse(single.wrapping)
        assertEquals(1, single.count)
        assertEquals(0, single.channelAt(0))
        assertNull(single.channelAt(1))
    }

    @Test
    fun withTwoChannelsEitherDirectionIsTheSameOneStepAway() {
        val two = ChannelPages(channels = 2, carousel = true)
        assertTrue(two.wrapping)
        assertEquals(1, two.channelAt(ORIGIN + 1))
        assertEquals(1, two.channelAt(ORIGIN - 1))
        assertEquals(ORIGIN + 1, two.pageOf(1, from = ORIGIN))
    }

    @Test
    fun noChannelsShowNothingAnywhere() {
        val none = ChannelPages(channels = 0, carousel = true)
        assertEquals(0, none.count)
        assertNull(none.channelAt(0))
    }
}
