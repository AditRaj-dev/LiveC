package com.livec.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/**
 * AndroidKeyStore-backed symmetric encryption helper.
 *
 * Generates (lazily) a non-exportable AES-256-GCM master key under the alias
 * "livec_secret_master".  Callers get [encrypt]/[decrypt]; the raw key bytes
 * never leave the hardware-backed keystore.
 *
 * Encrypted blobs are base64(iv ‖ ciphertext).  IV is always 12 bytes (GCM
 * standard).  If the key is wiped (e.g. user clears app data), [decrypt]
 * returns null → the caller is expected to regenerate the identity.
 */
object SecureStore {

    private const val MASTER_ALIAS = "livec_secret_master"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val IV_LEN = 12   // GCM standard 96-bit IV
    private const val TAG_LEN = 128 // GCM auth-tag bits

    // ── Key management ────────────────────────────────────────────────────────

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(MASTER_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        MASTER_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
        }
        return ks.getKey(MASTER_ALIAS, null) as SecretKey
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypts [plaintext] with the AndroidKeyStore master key.
     * Returns base64(iv ‖ ciphertext), no line-wrapping.
     */
    fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, masterKey())
        }
        val ciphertext = cipher.doFinal(plaintext)
        val blob = cipher.iv + ciphertext          // 12-byte IV prepended
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /**
     * Decrypts a blob previously produced by [encrypt].
     * Returns null if decryption fails (key wiped, tampered data, etc.).
     */
    fun decrypt(b64: String): ByteArray? = runCatching {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        require(all.size > IV_LEN) { "blob too short" }
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_LEN, iv))
        }.doFinal(ct)
    }.getOrNull()
}
