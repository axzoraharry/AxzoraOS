package com.axzora.os.chat

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for family chat.
 *
 * Key = PBKDF2-SHA256(familyCode, fixed app salt, 120k rounds) -> AES-256.
 * Every device that knows the family code derives the same key; the relay
 * server only ever sees ciphertext + IV. Message body is AES-GCM sealed, so
 * tampering is detected on decrypt.
 */
object CryptoBox {

    private const val SALT = "axzora-chat-v1"
    private val rng = SecureRandom()

    private val keyCache = HashMap<String, SecretKeySpec>()

    private fun key(familyCode: String): SecretKeySpec =
        keyCache.getOrPut(familyCode) {
            val spec = PBEKeySpec(familyCode.toCharArray(), SALT.toByteArray(), 120_000, 256)
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            SecretKeySpec(raw, "AES")
        }

    /** Returns (cipherB64, ivB64). */
    fun seal(familyCode: String, plaintext: String): Pair<String, String> {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(familyCode), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ct, Base64.NO_WRAP) to Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    /** Null if the ciphertext doesn't decrypt with this family code. */
    fun open(familyCode: String, cipherB64: String, ivB64: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE, key(familyCode),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)),
        )
        String(c.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
}
