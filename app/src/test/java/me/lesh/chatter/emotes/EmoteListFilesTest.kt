package me.lesh.chatter.emotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The emote lists kept between app starts. */
class EmoteListFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun files() = EmoteListFiles(folder.root.resolve("emote-lists"))

    private val wide = Emote("AngelThump", "566ca1a365dbbdab32ec055b", "https://cdn.betterttv.net/emote/566ca1a365dbbdab32ec055b/2x.webp", EmoteProvider.Bttv, aspectRatio = 3f)
    private val overlay = Emote("RainTime", "01F", "https://cdn.7tv.app/emote/01F/2x.webp", EmoteProvider.SevenTv, zeroWidth = true, isChannel = true, unlisted = true, author = "some\tone")
    private val unmeasured = Emote("LUL", "9", "https://cdn.frankerfacez.com/emote/9/2", EmoteProvider.Ffz, sizeKnown = false, ownerId = "123")

    @Test
    fun aListComesBackAsItWasWritten() {
        val files = files()
        files.write("71092938", ProviderEmotes(ffz = mapOf("LUL" to unmeasured), bttv = mapOf("AngelThump" to wide), sevenTv = mapOf("RainTime" to overlay)))

        val read = files.read("71092938")!!
        assertEquals(wide, read.bttv["AngelThump"])
        assertEquals(unmeasured, read.ffz["LUL"])
        // A tab in a name would break the line apart.
        assertEquals(overlay.copy(author = "some one"), read.sevenTv["RainTime"])
    }

    @Test
    fun aMissingOrForeignFileIsNoList() {
        val files = files()
        assertNull(files.read("123"))
        assertNull(EmoteListFiles.decode("0\nBttv\tx\t1\tu\t1.0\t1\t\t"))
    }

    @Test
    fun onlyChannelIdsAndTheGlobalListBecomeFiles() {
        val files = files()
        files.write("../escape", ProviderEmotes(bttv = mapOf("AngelThump" to wide)))
        assertFalse(folder.root.resolve("escape.tsv.gz").exists())
        assertNull(files.read("../escape"))
    }

    @Test
    fun aRemovedChannelLosesItsListButTheGlobalOneStays() {
        val files = files()
        val list = ProviderEmotes(bttv = mapOf("AngelThump" to wide))
        files.write(EmoteListFiles.GLOBAL, list)
        files.write("1", list)

        files.delete("1")

        assertTrue(files.read(EmoteListFiles.GLOBAL) != null)
        assertNull(files.read("1"))
    }
}
