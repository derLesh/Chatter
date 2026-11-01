package me.lesh.chatter.auth

import android.util.Log
import java.io.File
import me.lesh.chatter.BuildConfig

/**
 * Debug builds' way past Twitch's login page, which turns emulators away. `./gradlew devLogin`
 * leaves a token in [FILE] through `run-as`, which only adb can use, so no other app can log
 * Chatter into an account of its choosing. Every other build gets the empty one from
 * `src/withoutDevLogin`.
 */
object DevLogin {
    const val FILE = "dev-login-token"

    /** Logs in with the waiting token, if there is one, and deletes it either way. */
    suspend fun logInIfWaiting(filesDir: File, auth: AuthRepository) {
        val file = File(filesDir, FILE)
        if (!file.exists()) return
        val token = file.readText().trim()
        file.delete()
        if (token.isEmpty()) return
        auth.addToken(token, requireClient = BuildConfig.TWITCH_CLIENT_ID)
            .onSuccess { Log.i(TAG, "Logged in as ${auth.account?.login}") }
            .onFailure { Log.w(TAG, "Login with the token failed", it) }
    }

    private const val TAG = "DevLogin"
}
