package me.lesh.chatter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarColorsTest {
    private val red = 0xFFE01010.toInt()
    private val blue = 0xFF1040E0.toInt()
    private val grey = 0xFF808080.toInt()

    private fun picture(vararg parts: Pair<Int, Int>): IntArray =
        parts.flatMap { (color, count) -> List(count) { color } }.toIntArray()

    @Test
    fun theLargerColourComesFirst() {
        assertEquals(red to blue, AvatarColors.of(picture(red to 60, blue to 40)))
    }

    @Test
    fun aVividPatchBeatsAGreyBackground() {
        assertEquals(blue, AvatarColors.of(picture(grey to 90, blue to 10))?.first)
    }

    @Test
    fun aSingleColourGetsADarkerSecond() {
        assertEquals(red to 0xFF7B0808.toInt(), AvatarColors.of(picture(red to 100)))
    }

    @Test
    fun aGreyPictureUsesItsAverage() {
        assertEquals(grey, AvatarColors.of(picture(grey to 50))?.first)
    }

    @Test
    fun transparentPixelsAreLeftOut() {
        assertNull(AvatarColors.of(picture(0x00FF0000 to 50)))
        assertEquals(blue, AvatarColors.of(picture(0x00FF0000 to 90, blue to 10))?.first)
    }

    @Test
    fun aNeighbouringHueIsNotTheSecondColour() {
        val amber = 0xFFE0C010.toInt()
        val second = AvatarColors.of(picture(red to 60, amber to 30, blue to 10))?.second
        assertEquals(blue, second)
    }
}
