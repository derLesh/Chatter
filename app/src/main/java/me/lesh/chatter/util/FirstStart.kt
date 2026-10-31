package me.lesh.chatter.util

import android.content.Context

/**
 * Whether Chatter starts without any data of its own: a new install, or one whose data was cleared.
 * Only true before the first preference store is written, so ask before anything is built.
 */
fun isFirstStart(context: Context): Boolean = !context.filesDir.resolve("datastore").exists()
