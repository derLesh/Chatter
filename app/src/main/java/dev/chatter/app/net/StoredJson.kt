package dev.chatter.app.net

import android.util.Log

/**
 * A list or map the app keeps as JSON in a preferences store: [empty] when nothing is stored,
 * and null when something is stored that cannot be read.
 *
 * The two used to be the same answer, and that was how data got lost: whatever came next — one
 * new mention, one rule switched off — read the unreadable value as empty and wrote the empty
 * list back over it, with one entry in it. A value from a newer version of the app, or one a
 * future bug writes wrong, is still somebody's rules and inbox. So a change refuses to write over
 * what it could not read (see the repositories' update functions), and a screen shows it as
 * empty while it stays on disk for a version that can read it.
 */
inline fun <reified T> decodeStored(raw: String?, empty: T, what: String): T? {
    if (raw == null) return empty
    return try {
        AppJson.decodeFromString<T>(raw)
    } catch (e: IllegalArgumentException) {
        // SerializationException is one. The class only: the message quotes what was stored.
        Log.e("StoredJson", "The stored $what cannot be read (${e.javaClass.simpleName}); it is kept as it is")
        null
    }
}
