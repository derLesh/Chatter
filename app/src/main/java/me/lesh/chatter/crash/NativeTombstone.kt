package me.lesh.chatter.crash

/**
 * What a native crash's tombstone says, in the words of Android's own crash text: the signal,
 * the abort message and the crashing thread's frames. Android hands the tombstone over as a
 * protocol buffer (`tombstone.proto` in AOSP's debuggerd); only the few fields read here are
 * decoded, without a library for the format.
 */
internal object NativeTombstone {
    /** One thread's frames are rarely more; the top ones say where. */
    private const val MAX_FRAMES = 32

    /** Null when [bytes] cannot be read as a tombstone. */
    fun describe(bytes: ByteArray): String? = runCatching {
        val tombstone = Message.parse(bytes)
        val signal = tombstone.message(TOMBSTONE_SIGNAL)
        val crashingTid = tombstone.varint(TOMBSTONE_TID)
        val thread = tombstone.messages(TOMBSTONE_THREADS)
            .mapNotNull { it.message(MAP_VALUE) }
            .firstOrNull { it.varint(THREAD_ID) == crashingTid }
        buildString {
            append("signal ${signal?.varint(SIGNAL_NUMBER) ?: "?"} (${signal?.string(SIGNAL_NAME) ?: "?"})")
            // A field holding 0 is left out of the bytes, so a missing number is 0.
            signal?.string(SIGNAL_CODE_NAME)?.let { append(", code ${signal.varint(SIGNAL_CODE) ?: 0} ($it)") }
            tombstone.string(TOMBSTONE_ABORT_MESSAGE)?.takeIf { it.isNotBlank() }?.let { append("\nAbort message: '$it'") }
            if (thread != null) {
                append("\nThread: ${thread.string(THREAD_NAME) ?: "?"} (tid $crashingTid)")
                thread.messages(THREAD_BACKTRACE).take(MAX_FRAMES).forEachIndexed { i, frame ->
                    append("\n  #%02d pc %016x  %s".format(i, frame.varint(FRAME_REL_PC) ?: 0L, frame.string(FRAME_FILE) ?: "?"))
                    frame.string(FRAME_FUNCTION)?.takeIf { it.isNotEmpty() }?.let { append(" ($it+${frame.varint(FRAME_FUNCTION_OFFSET) ?: 0})") }
                }
            }
        }
    }.getOrNull()

    // Field numbers from tombstone.proto.
    private const val TOMBSTONE_TID = 6
    private const val TOMBSTONE_SIGNAL = 10
    private const val TOMBSTONE_ABORT_MESSAGE = 14
    private const val TOMBSTONE_THREADS = 16
    private const val MAP_VALUE = 2
    private const val SIGNAL_NUMBER = 1
    private const val SIGNAL_NAME = 2
    private const val SIGNAL_CODE = 3
    private const val SIGNAL_CODE_NAME = 4
    private const val THREAD_ID = 1
    private const val THREAD_NAME = 2
    private const val THREAD_BACKTRACE = 4
    private const val FRAME_REL_PC = 1
    private const val FRAME_FUNCTION = 4
    private const val FRAME_FUNCTION_OFFSET = 5
    private const val FRAME_FILE = 6

    /** A protocol buffer message's fields by number: numbers for varints, bytes for everything length-delimited. */
    private class Message(private val fields: Map<Int, List<Any>>) {
        fun varint(field: Int): Long? = fields[field]?.firstOrNull { it is Long } as Long?
        fun string(field: Int): String? = (fields[field]?.firstOrNull { it is ByteArray } as ByteArray?)?.decodeToString()
        fun message(field: Int): Message? = messages(field).firstOrNull()
        fun messages(field: Int): List<Message> = fields[field].orEmpty().filterIsInstance<ByteArray>().map(::parse)

        companion object {
            fun parse(bytes: ByteArray): Message {
                val fields = mutableMapOf<Int, MutableList<Any>>()
                var i = 0
                fun varint(): Long {
                    var result = 0L
                    var shift = 0
                    while (true) {
                        val b = bytes[i++].toInt()
                        result = result or ((b and 0x7f).toLong() shl shift)
                        if (b and 0x80 == 0) return result
                        shift += 7
                        require(shift < 64)
                    }
                }
                while (i < bytes.size) {
                    val key = varint()
                    val field = (key ushr 3).toInt()
                    when ((key and 7).toInt()) {
                        0 -> fields.getOrPut(field) { mutableListOf() } += varint()
                        1 -> i += 8
                        2 -> {
                            val length = varint().toInt()
                            require(length >= 0 && i + length <= bytes.size)
                            fields.getOrPut(field) { mutableListOf() } += bytes.copyOfRange(i, i + length)
                            i += length
                        }
                        5 -> i += 4
                        else -> error("wire type ${key and 7}")
                    }
                }
                return Message(fields)
            }
        }
    }
}
