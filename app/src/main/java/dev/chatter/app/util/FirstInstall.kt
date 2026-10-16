package dev.chatter.app.util

import android.content.Context

/**
 * Whether Chatter has not been updated since it was installed. Android tells the two apart by these
 * timestamps.
 */
fun isFirstInstall(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.firstInstallTime == info.lastUpdateTime
}.getOrDefault(true)
