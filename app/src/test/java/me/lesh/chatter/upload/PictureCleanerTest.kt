package me.lesh.chatter.upload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * What leaves a picture before it is uploaded: each made-up file below carries "SECRET" where a
 * camera puts the location, the time or a comment, and the parts that draw the picture.
 */
class PictureCleanerTest {
    private fun bytes(vararg parts: Any): ByteArray = ByteArrayOutputStream().apply {
        parts.forEach { part ->
            when (part) {
                is ByteArray -> write(part)
                is String -> write(part.toByteArray(Charsets.ISO_8859_1))
                is Int -> write(part)
                else -> error("Unknown part $part")
            }
        }
    }.toByteArray()

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    private fun jpegSegment(marker: Int, body: ByteArray) =
        bytes(0xFF, marker, (body.size + 2) shr 8, (body.size + 2) and 0xFF, body)

    /** Big-endian TIFF with Orientation 6 (turned right) and a pointer to GPS data. */
    private val exif = bytes(
        "Exif", 0, 0, "MM", 0, 42, 0, 0, 0, 8,
        0, 2,
        0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0,
        0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, 38,
        0, 0, 0, 0,
        "SECRET GPS 52.52N 13.40E",
    )
    private val jfif = jpegSegment(0xE0, bytes("JFIF", 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val quantization = jpegSegment(0xDB, bytes(0, ByteArray(64) { 1 }))
    private val scan = bytes(0xFF, 0xDA, 0, 8, 1, 1, 0, 0, 63, 0, "IMAGEDATA", 0xFF, 0xD9)

    @Test
    fun aJpegKeepsOnlyItsRotation() {
        val photo = bytes(
            0xFF, 0xD8, jfif, jpegSegment(0xE1, exif), jpegSegment(0xFE, bytes("SECRET comment")),
            jpegSegment(0xED, bytes("Photoshop 3.0 SECRET")), jpegSegment(0xE2, bytes("ICC_PROFILE", 0, "colours")),
            quantization, scan,
        )
        val clean = PictureCleaner.clean(photo, "image/jpeg")!!
        assertFalse(clean.has("SECRET"))
        assertTrue(clean.has("ICC_PROFILE"))
        assertTrue(clean.has("IMAGEDATA"))
        // Start, JFIF first, then the rotation alone, then the rest as it was.
        val rotation = jpegSegment(0xE1, bytes("Exif", 0, 0, "MM", 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0))
        val expectedStart = bytes(0xFF, 0xD8, jfif, rotation)
        assertArrayEquals(expectedStart, clean.copyOf(expectedStart.size))
        assertTrue(clean.copyOfRange(clean.size - scan.size, clean.size).contentEquals(scan))
    }

    /** An Ultra HDR photo: the gain map after the first picture brings metadata of its own. */
    @Test
    fun whatFollowsTheJpegsEndGoes() {
        val trailer = bytes(0xFF, 0xD8, jpegSegment(0xE1, bytes("http://ns.adobe.com/xap/1.0/", 0, "SECRET hdrgm")), scan)
        val clean = PictureCleaner.clean(bytes(0xFF, 0xD8, quantization, scan, trailer), "image/jpeg")!!
        assertArrayEquals(bytes(0xFF, 0xD8, quantization, scan), clean)
    }

    @Test
    fun anUprightJpegKeepsNoExifAtAll() {
        val upright = bytes("Exif", 0, 0, "II", 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, "SECRET")
        val clean = PictureCleaner.clean(bytes(0xFF, 0xD8, jpegSegment(0xE1, upright), quantization, scan), "image/jpeg")!!
        assertArrayEquals(bytes(0xFF, 0xD8, quantization, scan), clean)
    }

    private fun pngChunk(type: String, data: ByteArray) = bytes(0, 0, data.size shr 8, data.size and 0xFF, type, data, 0, 0, 0, 0)
    private val pngSignature = bytes(0x89, "PNG", 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun aPngLosesItsTextExifAndTime() {
        val header = pngChunk("IHDR", ByteArray(13))
        val image = pngChunk("IDAT", bytes("IMAGEDATA"))
        val end = pngChunk("IEND", ByteArray(0))
        val png = bytes(
            pngSignature, header, pngChunk("tEXt", bytes("Comment", 0, "SECRET")), pngChunk("eXIf", bytes("MM SECRET")),
            pngChunk("tIME", bytes(7, 0xE6, 11, 13, 12, 0, 0)), pngChunk("iTXt", bytes("XML:com.adobe.xmp SECRET")), image, end,
        )
        assertArrayEquals(bytes(pngSignature, header, image, end), PictureCleaner.clean(png, "image/png"))
    }

    private fun webpChunk(type: String, data: ByteArray) =
        bytes(type, data.size and 0xFF, (data.size shr 8) and 0xFF, 0, 0, data, if (data.size % 2 == 1) bytes(0) else ByteArray(0))

    private fun riff(body: ByteArray) =
        bytes("RIFF", (body.size + 4) and 0xFF, ((body.size + 4) shr 8) and 0xFF, 0, 0, "WEBP", body)

    @Test
    fun aWebpLosesExifAndXmpAndItsHeaderStopsPromisingThem() {
        val header = webpChunk("VP8X", bytes(0x10 or 0x08 or 0x04, 0, 0, 0, 1, 0, 0, 1, 0, 0))
        val image = webpChunk("VP8L", bytes("ODD"))
        val webp = riff(bytes(header, image, webpChunk("EXIF", bytes("SECRET GPS")), webpChunk("XMP ", bytes("<x>SECRET</x>"))))
        val clean = PictureCleaner.clean(webp, "image/webp")!!
        // Only the alpha flag is left in the header; the image and its padding byte stay.
        assertArrayEquals(riff(bytes(webpChunk("VP8X", bytes(0x10, 0, 0, 0, 1, 0, 0, 1, 0, 0)), image)), clean)
    }

    @Test
    fun aGifKeepsItsLoopButLosesCommentsAndXmp() {
        val start = bytes("GIF89a", 1, 0, 1, 0, 0x80, 0, 0, 0, 0, 0, 255, 255, 255)
        val loop = bytes(0x21, 0xFF, 11, "NETSCAPE2.0", 3, 1, 0, 0, 0)
        val frame = bytes(0x21, 0xF9, 4, 0, 10, 0, 0, 0, 0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0)
        val gif = bytes(
            start, loop, bytes(0x21, 0xFE, 6, "SECRET", 0), bytes(0x21, 0xFF, 11, "XMP DataXMP", 6, "SECRET", 0), frame, 0x3B,
        )
        assertArrayEquals(bytes(start, loop, frame, 0x3B), PictureCleaner.clean(gif, "image/gif"))
    }

    @Test
    fun theFormatComesFromThePictureItself() {
        assertEquals("image/jpeg", PictureCleaner.typeOf(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/png", PictureCleaner.typeOf(bytes(pngSignature, 0)))
        assertEquals("image/webp", PictureCleaner.typeOf(riff(ByteArray(0))))
        assertEquals("image/gif", PictureCleaner.typeOf(bytes("GIF87a")))
        // HEIC and the like are drawn anew instead.
        assertNull(PictureCleaner.typeOf(bytes(0, 0, 0, 24, "ftypheic")))
        assertNull(PictureCleaner.clean(bytes(0, 0, 0, 24, "ftypheic"), "image/heic"))
    }

    /** A picture that breaks off is refused, never passed on as it came. */
    @Test(expected = UnreadablePictureException::class)
    fun aBrokenJpegIsRefused() {
        PictureCleaner.clean(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x40, 0, "SECRET"), "image/jpeg")
    }

    @Test(expected = UnreadablePictureException::class)
    fun aGifWithoutEndIsRefused() {
        PictureCleaner.clean(bytes("GIF89a", 1, 0, 1, 0, 0, 0, 0, 0x21, 0xFE, 6, "SECRET"), "image/gif")
    }
}
