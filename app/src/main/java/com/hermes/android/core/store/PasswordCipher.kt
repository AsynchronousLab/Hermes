package com.hermes.android.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the gateway password with a Keystore-backed AES-GCM key.
 *
 * The gateway authenticates with a username/password pair rather than a bearer
 * token, so the credential has to be persisted for the app to reconnect without
 * prompting. Storing it verbatim would leave it readable to anything that can
 * read the app's data directory or a backup, so the value is encrypted at rest.
 *
 * If the keystore entry is ever lost (device credentials reset, restore to a new
 * device) decryption fails and [decrypt] returns null, which simply asks the
 * user to re-enter the password.
 */
class PasswordCipher {

    private companion object {
        const val KEY_ALIAS = "hermes_backend_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Returns `iv || ciphertext`, base64 encoded, or null if encryption fails. */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }.getOrNull()

    /**
 * Returns null when the payload is unreadable, so callers re-prompt instead of
 * crashing. Decoding lives inside the guard too — a truncated or corrupt
 * payload would otherwise throw out of `config.first()` during startup.
 */
    fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        if (raw.size <= IV_LENGTH) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, raw, 0, IV_LENGTH),
        )
        String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
    }.getOrNull()
}