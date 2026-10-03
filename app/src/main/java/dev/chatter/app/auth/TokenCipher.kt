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

/** Result of decrypting a token; see [TokenCipher.open]. */
sealed interface Decrypted {
    data class Plain(val text: String) : Decrypted {
        /** Leaves out the token; see [Account.toString]. */
        override fun toString() = "Plain(‹redacted›)"
    }

    /** Can never be read again: the key is gone, or the text was not encrypted by us. */
    data object Lost : Decrypted

    /** The keystore did not answer this time; try again later. */
    data object Unavailable : Decrypted
}

/** Encrypts the OAuth tokens with a key that never leaves the Android Keystore. */
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
     * Decrypts [encoded]. A lost key and an unavailable keystore are told apart: the keystore can
     * fail to answer right after boot, and treating that as lost would log the user out and drop
     * the inbox.
     */
    fun open(encoded: String): Decrypted = try {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        Decrypted.Plain(String(cipher.doFinal(bytes, 12, bytes.size - 12)))
    } catch (e: Exception) {
        // Class name only; the provider's message could contain the input.
        val lost = e is AEADBadTagException || e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException || e is IllegalArgumentException
        Log.w(TAG, "Could not decrypt a token (${e.javaClass.simpleName}); ${if (lost) "it is lost" else "will try again"}")
        if (lost) Decrypted.Lost else Decrypted.Unavailable
    }

    /** The token, or null if it could not be read for whatever reason. */
    fun decrypt(encoded: String): String? = (open(encoded) as? Decrypted.Plain)?.text
}
