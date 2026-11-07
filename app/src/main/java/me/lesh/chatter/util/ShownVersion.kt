package me.lesh.chatter.util

import me.lesh.chatter.BuildConfig

/**
 * The version as the app shows it, with "-dev" on the debug build. Kept out of versionName, which
 * the update check and the changelog read as a plain "1.2.3".
 */
val shownVersion: String = BuildConfig.VERSION_NAME + if (BuildConfig.DEV_BUILD) "-dev" else ""
