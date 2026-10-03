package dev.chatter.app.net

import android.util.Log

/**
 * Decodes a JSON list or map from a preferences store: [empty] when nothing is stored, null when
 * the stored value cannot be read.
 *
 * Callers must not write over a null result: the value may come from a newer app version or a bug,
 * and is still the user's data. Screens show it as empty meanwhile.
 */
inline fun <reified T> decodeStored(raw: String?, empty: T, what: String): T? {
    if (raw == null) return empty
    return try {
        AppJson.decodeFromString<T>(raw)
    } catch (e: IllegalArgumentException) {
        // SerializationException is one. Class name only; the message quotes the stored value.
        Log.e("StoredJson", "The stored $what cannot be read (${e.javaClass.simpleName}); it is kept as it is")
        null
    }
}
