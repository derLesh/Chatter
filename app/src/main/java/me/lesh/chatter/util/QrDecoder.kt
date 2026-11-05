package me.lesh.chatter.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** Finds a QR code in one camera frame, on the phone, with ZXing. */
object QrDecoder {
    // A website in dark mode may draw the code light on dark. Only MultiFormatReader tries that.
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.ALSO_INVERTED to true,
    )

    /**
     * [luminance] is the frame's brightness plane, one byte per pixel, rows [rowStride] bytes apart;
     * the camera may pad each row beyond [width]. Null when the frame holds no readable code.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            // A reader keeps state between calls, so each frame gets its own.
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        }
    }
}
