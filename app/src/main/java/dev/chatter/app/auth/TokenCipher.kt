package dev.chatter.app.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What came of decrypting a token; see [TokenCipher.open]. */
sealed interface Decrypted {
    data class Plain(val text: String) : Decrypted {
        /** Without the token, for the same reason as [Account.toString]. */
        override fun toString() = "Plain(‹redacted›)"
    }

    /** It can never be read again: the key it was made with is gone, or the text is not one of ours. */
    data object Lost : Decrypted

    /** The keystore did not answer this time. Nothing is wrong with the token; ask again later. */
    data object Unavailable : Decrypted
}

/** Encrypts the OAuth token with a key that never leaves the Android Keystore. */
object TokenCipher {
    private const val TAG = "TokenCipher"
    private const val ALIAS = "chatter_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Decrypts [encoded], and says why when it cannot.
     *
     * The two failures mean opposite things. A token whose key was replaced is gone for good,
     * and the account it belonged to with it. But the keystore is a system service, and right
     * after the phone starts, or while it is busy, it can fail to answer at all — taking that as
     * gone as well logged the user out and threw away their inbox for a moment's hiccup.
     */
    fun open(encoded: String): Decrypted = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        Decrypted.Plain(String(cipher.doFinal(bytes, 12, bytes.size - 12)))
    } catch (e: Exception) {
        // The class name only: a message from the crypto provider could quote what it was given.
        val lost = e is AEADBadTagException || e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException || e is IllegalArgumentException
        Log.w(TAG, "Could not decrypt a token (${e.javaClass.simpleName}); ${if (lost) "it is lost" else "will try again"}")
        if (lost) Decrypted.Lost else Decrypted.Unavailable
    }

    /** The token, or null whichever way it could not be read. */
    fun decrypt(encoded: String): String? = (open(encoded) as? Decrypted.Plain)?.text
}
