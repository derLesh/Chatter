package me.lesh.chatter.auth

import java.io.File

/** Every build but debug: never takes a token from outside. The real one is in `src/debug`. */
object DevLogin {
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun logInIfWaiting(filesDir: File, auth: AuthRepository) = Unit
}
