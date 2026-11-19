package me.lesh.chatter.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The few fields of a tombstone that go into a crash, built here the way debuggerd writes them. */
class NativeTombstoneTest {
    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun number(field: Int, value: Long) = apply {
            varint((field shl 3).toLong())
            varint(value)
        }

        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            out.write(value)
        }

        fun text(field: Int, value: String) = bytes(field, value.toByteArray())
        fun message(field: Int, value: Proto) = bytes(field, value.toBytes())
        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun frame(pc: Long, file: String, function: String? = null) = Proto().number(1, pc).apply {
        if (function != null) text(4, function).number(5, 28)
    }.text(6, file)

    @Test
    fun theSignalAndTheCrashingThreadsFramesComeOut() {
        val main = Proto().number(1, 4242).text(2, "main")
            .message(4, frame(0x5c8bc, "/apex/com.android.runtime/lib64/bionic/libc.so", "syscall"))
            .message(4, frame(0x1234, "/system/lib64/libhwui.so"))
        val other = Proto().number(1, 4300).text(2, "RenderThread")
        val tombstone = Proto()
            .number(1, 1)
            .number(6, 4242)
            .message(10, Proto().number(1, 11).text(2, "SIGSEGV").number(3, 1).text(4, "SEGV_MAPERR"))
            .text(14, "boom")
            .message(16, Proto().number(1, 4300).message(2, other))
            .message(16, Proto().number(1, 4242).message(2, main))
        assertEquals(
            """
            signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)
            Abort message: 'boom'
            Thread: main (tid 4242)
              #00 pc 000000000005c8bc  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
              #01 pc 0000000000001234  /system/lib64/libhwui.so
            """.trimIndent(),
            NativeTombstone.describe(tombstone.toBytes()),
        )
    }

    @Test
    fun somethingElseIsNotATombstone() {
        assertNull(NativeTombstone.describe(byteArrayOf(0x0a, 0x7f, 0x01)))
    }
}
