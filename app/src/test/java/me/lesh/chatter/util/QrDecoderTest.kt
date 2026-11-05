package me.lesh.chatter.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    @Test
    fun aCodeInTheFrameIsRead() {
        val frame = frameOf("abcdefghij0123456789abcdefgh", size = 300, rowStride = 300)
        assertEquals("abcdefghij0123456789abcdefgh", QrDecoder.decode(frame, 300, 300, 300))
    }

    /** Cameras often pad each row; the padding must not be read as picture. */
    @Test
    fun paddedRowsAreRead() {
        val frame = frameOf("chatter://login?token=abc123", size = 300, rowStride = 320)
        assertEquals("chatter://login?token=abc123", QrDecoder.decode(frame, 320, 300, 300))
    }

    @Test
    fun aLightCodeOnDarkIsRead() {
        val frame = frameOf("abc123", size = 300, rowStride = 300, inverted = true)
        assertEquals("abc123", QrDecoder.decode(frame, 300, 300, 300))
    }

    @Test
    fun anEmptyFrameHasNoCode() {
        assertNull(QrDecoder.decode(ByteArray(300 * 300) { 0x7f }, 300, 300, 300))
    }

    /** A grey frame, [size] pixels square, with the QR code of [text] drawn into it. */
    private fun frameOf(text: String, size: Int, rowStride: Int, inverted: Boolean = false): ByteArray {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val dark: Byte = if (inverted) 0xf0.toByte() else 0x10
        val light: Byte = if (inverted) 0x10 else 0xf0.toByte()
        return ByteArray(rowStride * size) { i ->
            val x = i % rowStride
            val y = i / rowStride
            when {
                x >= size -> 0
                code[x, y] -> dark
                else -> light
            }
        }
    }
}
