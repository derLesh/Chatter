package me.lesh.chatter.upload

import java.io.ByteArrayOutputStream
import java.io.IOException

/** A picture that could not be read as the format it claims to be. */
class UnreadablePictureException(message: String) : IOException(message)

/**
 * Takes out of a picture what can tell where, when and with what it was taken: EXIF (with the
 * location), XMP, IPTC, comments and the like. JPEG, PNG, WebP and GIF are cleaned without
 * touching the image data, so nothing is recompressed and animations stay; [clean] returns null
 * for every other format, which then has to be drawn anew. Free of Android for the tests.
 */
object PictureCleaner {
    /** The format [bytes] are in, read from their first bytes; null for anything but the four above. */
    fun typeOf(bytes: ByteArray): String? = when {
        bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF -> "image/jpeg"
        startsWith(bytes, 0, PNG_SIGNATURE) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
        else -> null
    }

    /** [bytes] without their metadata, or null when [type] is not one of the four formats above. */
    fun clean(bytes: ByteArray, type: String): ByteArray? = when (type.lowercase()) {
        "image/jpeg", "image/jpg" -> jpeg(bytes)
        "image/png" -> png(bytes)
        "image/webp" -> webp(bytes)
        "image/gif" -> gif(bytes)
        else -> null
    }

    // JPEG: segments up to the image data, each a marker and a length that counts itself.

    // Kept: APP0 (JFIF), APP2 with a colour profile and APP14 (Adobe), which are needed to show the
    // picture right, and every segment that is not an APPn or a comment.
    private const val APP0_JFIF = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP14_ADOBE = 0xEE
    private const val COMMENT = 0xFE
    private const val START_OF_SCAN = 0xDA
    private val ICC_PROFILE = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
    private val EXIF = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun jpeg(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) throw UnreadablePictureException("Not a JPEG")
        val kept = ByteArrayOutputStream(bytes.size)
        // Where the rotation goes: right after the start, or after a JFIF segment that must come first.
        var rotationAt = 0
        var orientation = 1
        var pos = 2
        var reachedImage = false
        while (pos + 4 <= bytes.size) {
            if (u8(bytes, pos) != 0xFF) throw UnreadablePictureException("Broken JPEG segment")
            val marker = u8(bytes, pos + 1)
            // Fill bytes between segments.
            if (marker == 0xFF) {
                pos++
                continue
            }
            if (marker == START_OF_SCAN) {
                // Up to the end marker: an Ultra HDR photo carries a second picture with its own
                // metadata after it, and some apps append more. Image data never holds FF D9.
                val end = endOfImage(bytes, pos + 2) ?: bytes.size
                kept.write(bytes, pos, end - pos)
                reachedImage = true
                break
            }
            val length = u16be(bytes, pos + 2)
            val end = pos + 2 + length
            if (length < 2 || end > bytes.size) throw UnreadablePictureException("Broken JPEG segment")
            val keep = when (marker) {
                APP1 -> {
                    if (startsWith(bytes, pos + 4, EXIF)) orientation = exifOrientation(bytes, pos + 4 + EXIF.size, end) ?: orientation
                    false
                }
                APP0_JFIF, APP14_ADOBE -> true
                APP2 -> startsWith(bytes, pos + 4, ICC_PROFILE)
                in 0xE3..0xEF, COMMENT -> false
                else -> true
            }
            if (keep) {
                if (marker == APP0_JFIF && kept.size() == 0) rotationAt = end - pos
                kept.write(bytes, pos, end - pos)
            }
            pos = end
        }
        if (!reachedImage) throw UnreadablePictureException("JPEG without image data")
        val segments = kept.toByteArray()
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2)
        out.write(segments, 0, rotationAt)
        // The only EXIF that stays: without it a photo taken upright would show on its side.
        if (orientation != 1) out.write(orientationOnly(orientation))
        out.write(segments, rotationAt, segments.size - rotationAt)
        return out.toByteArray()
    }

    /** The position after the first end-of-image marker from [start] on; null when the file has none. */
    private fun endOfImage(bytes: ByteArray, start: Int): Int? {
        for (i in start until bytes.size - 1) {
            if (u8(bytes, i) == 0xFF && u8(bytes, i + 1) == 0xD9) return i + 2
        }
        return null
    }

    /** The Orientation tag of the TIFF block from [start] to [end]; null when it has none. */
    private fun exifOrientation(bytes: ByteArray, start: Int, end: Int): Int? {
        if (start + 8 > end) return null
        val little = when {
            bytes[start] == 'I'.code.toByte() && bytes[start + 1] == 'I'.code.toByte() -> true
            bytes[start] == 'M'.code.toByte() && bytes[start + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        fun u16(at: Int) = if (little) u8(bytes, at) or (u8(bytes, at + 1) shl 8) else u16be(bytes, at)
        fun u32(at: Int) = if (little) u16(at) or (u16(at + 2) shl 16) else (u16(at) shl 16) or u16(at + 2)
        val ifd = start + u32(start + 4)
        if (ifd + 2 > end) return null
        val entries = u16(ifd)
        for (i in 0 until entries) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > end) return null
            if (u16(entry) == ORIENTATION_TAG) return u16(entry + 8).takeIf { it in 1..8 }
        }
        return null
    }

    private const val ORIENTATION_TAG = 0x0112

    /** An APP1 segment with nothing but the Orientation tag, so a photo turned on its side stays upright. */
    private fun orientationOnly(orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // big-endian, IFD0 at 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // Orientation, SHORT, 1
            0, 0, 0, 0, // no next IFD
        )
        val body = EXIF + tiff
        return byteArrayOf(0xFF.toByte(), APP1.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
    }

    // PNG: chunks of length, type, data and CRC after an eight-byte signature.

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Text, the time it was made and EXIF; the rest draws the picture. */
    private val PNG_DROPPED = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

    private fun png(bytes: ByteArray): ByteArray {
        if (!startsWith(bytes, 0, PNG_SIGNATURE)) throw UnreadablePictureException("Not a PNG")
        val out = ByteArrayOutputStream(bytes.size)
        out.write(PNG_SIGNATURE)
        var pos = PNG_SIGNATURE.size
        var ended = false
        while (pos + 12 <= bytes.size) {
            val length = u32be(bytes, pos)
            val end = pos + 12 + length
            if (length < 0 || end > bytes.size) throw UnreadablePictureException("Broken PNG chunk")
            val kind = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (kind !in PNG_DROPPED) out.write(bytes, pos, end - pos)
            pos = end
            if (kind == "IEND") {
                ended = true
                break
            }
        }
        if (!ended) throw UnreadablePictureException("PNG without end")
        return out.toByteArray()
    }

    // WebP: a RIFF file of chunks, each a four-letter name, a little-endian size and the data, padded to even.

    private const val VP8X_EXIF = 0x08
    private const val VP8X_XMP = 0x04

    private fun webp(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(bytes, 8, 4, Charsets.ISO_8859_1) != "WEBP") {
            throw UnreadablePictureException("Not a WebP")
        }
        val chunks = ByteArrayOutputStream(bytes.size)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val kind = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val size = u32le(bytes, pos + 4)
            val end = pos + 8 + size + (size and 1)
            if (size < 0 || pos + 8 + size > bytes.size) throw UnreadablePictureException("Broken WebP chunk")
            val padded = minOf(end, bytes.size)
            when (kind) {
                "EXIF", "XMP " -> Unit
                "VP8X" -> {
                    val chunk = bytes.copyOfRange(pos, padded)
                    // The header says which chunks follow; it must not promise the ones taken out.
                    chunk[8] = (chunk[8].toInt() and (VP8X_EXIF or VP8X_XMP).inv()).toByte()
                    chunks.write(chunk)
                }
                else -> chunks.write(bytes, pos, padded - pos)
            }
            pos = end
        }
        val body = chunks.toByteArray()
        val riffSize = 4 + body.size
        return "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(riffSize) + "WEBP".toByteArray(Charsets.ISO_8859_1) + body
    }

    // GIF: header, screen descriptor and colour table, then blocks until the trailer.

    private const val GIF_EXTENSION = 0x21
    private const val GIF_IMAGE = 0x2C
    private const val GIF_TRAILER = 0x3B
    private const val GIF_COMMENT = 0xFE
    private const val GIF_APPLICATION = 0xFF

    private fun gif(bytes: ByteArray): ByteArray {
        if (bytes.size < 13 || !String(bytes, 0, 3, Charsets.ISO_8859_1).equals("GIF", ignoreCase = false)) {
            throw UnreadablePictureException("Not a GIF")
        }
        val out = ByteArrayOutputStream(bytes.size)
        val packed = u8(bytes, 10)
        var pos = 13 + if (packed and 0x80 != 0) 3 * (1 shl ((packed and 0x07) + 1)) else 0
        if (pos > bytes.size) throw UnreadablePictureException("Broken GIF header")
        out.write(bytes, 0, pos)
        while (pos < bytes.size) {
            when (u8(bytes, pos)) {
                GIF_TRAILER -> {
                    out.write(GIF_TRAILER)
                    return out.toByteArray()
                }
                GIF_EXTENSION -> {
                    if (pos + 2 > bytes.size) throw UnreadablePictureException("Broken GIF extension")
                    val label = u8(bytes, pos + 1)
                    val end = skipSubBlocks(bytes, pos + 2)
                    // Loop counts (NETSCAPE2.0) and frame timing stay; comments and XMP go.
                    val drop = label == GIF_COMMENT ||
                        (label == GIF_APPLICATION && pos + 14 <= bytes.size && String(bytes, pos + 3, 11, Charsets.ISO_8859_1) == "XMP DataXMP")
                    if (!drop) out.write(bytes, pos, end - pos)
                    pos = end
                }
                GIF_IMAGE -> {
                    if (pos + 10 > bytes.size) throw UnreadablePictureException("Broken GIF frame")
                    val local = u8(bytes, pos + 9)
                    val table = if (local and 0x80 != 0) 3 * (1 shl ((local and 0x07) + 1)) else 0
                    // Descriptor, colour table, the LZW code size, then the data blocks.
                    val end = skipSubBlocks(bytes, pos + 10 + table + 1)
                    out.write(bytes, pos, end - pos)
                    pos = end
                }
                else -> throw UnreadablePictureException("Broken GIF block")
            }
        }
        throw UnreadablePictureException("GIF without end")
    }

    /** The position after the data sub-blocks starting at [start], each led by its size, the last one empty. */
    private fun skipSubBlocks(bytes: ByteArray, start: Int): Int {
        var pos = start
        while (true) {
            if (pos >= bytes.size) throw UnreadablePictureException("Broken GIF data")
            val size = u8(bytes, pos)
            pos += 1 + size
            if (size == 0) return pos
        }
    }

    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xFF
    private fun u16be(bytes: ByteArray, at: Int) = (u8(bytes, at) shl 8) or u8(bytes, at + 1)
    private fun u32be(bytes: ByteArray, at: Int) = (u16be(bytes, at) shl 16) or u16be(bytes, at + 2)
    private fun u32le(bytes: ByteArray, at: Int) = u8(bytes, at) or (u8(bytes, at + 1) shl 8) or (u8(bytes, at + 2) shl 16) or (u8(bytes, at + 3) shl 24)
    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun startsWith(bytes: ByteArray, at: Int, prefix: ByteArray): Boolean =
        at + prefix.size <= bytes.size && prefix.indices.all { bytes[at + it] == prefix[it] }
}
