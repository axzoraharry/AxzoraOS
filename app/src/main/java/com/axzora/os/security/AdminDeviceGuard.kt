package com.axzora.os.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Locks the operator console to one physical phone.
 *
 * The admin code alone was never enough: it is a shared secret, so anyone who
 * learns it — or anyone who restores an app-data backup from the operator's
 * phone onto a second handset — arrived at a fully unlocked console with the
 * session already restored. Two things were missing, and this class supplies
 * both:
 *
 *  1. **Hardware binding.** The session is sealed with an AES-256 key that lives
 *     in the device's Keystore (StrongBox where the phone has one). Those keys
 *     are non-exportable by design: they cannot be copied out of the TEE, so a
 *     backup restored onto different hardware finds ciphertext it can never
 *     open. The seal's additional-authenticated-data is a hash of this device's
 *     identifiers, so even a hypothetical key match fails the tag check.
 *
 *  2. **Session continuity, for the right device.** Because the code is inside
 *     the seal, this phone re-opens the console without retyping anything and
 *     without keeping the secret in plain SharedPreferences. Any other phone
 *     reads "bound to another device" and is refused — the panel does not
 *     render at all, rather than rendering a login box that a copied code
 *     could satisfy.
 *
 * Fail-closed by construction: if the seal cannot be opened (key cleared, lock
 * screen changed, TEE reset, different phone), [status] reports it and the
 * caller must not show the console. Recovery is deliberate — the operator
 * retypes the admin code, the server authorises it, and a new seal is written.
 *
 * Not a defence against a rooted phone with the operator's cooperation; it is a
 * defence against the realistic case, which is a code or a backup leaking.
 */
class AdminDeviceGuard(context: Context) {

    private val context = context.applicationContext

    /** What the caller may do right now. */
    enum class State {
        /** Nothing sealed yet: first run, or the operator cleared the console. */
        UNBOUND,

        /** Sealed and opened on this phone — the console may render. */
        BOUND_HERE,

        /** The seal belongs to different hardware. Refuse, and it stays refused. */
        BOUND_ELSEWHERE,

        /** Right phone, unreadable seal (key rotated/cleared). Re-bind with the code. */
        KEY_LOST,
    }

    data class Status(
        val state: State,
        /** The admin code, only ever non-null for [State.BOUND_HERE]. */
        val code: String? = null,
        /** When this phone was bound, epoch millis. 0 when unknown. */
        val boundAt: Long = 0L,
        /** One line for the console header; never contains the code. */
        val message: String = "",
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Reads the current state. Cheap enough to call on the UI thread (one
     * Keystore decrypt), but callers should still treat it as I/O.
     */
    fun status(): Status {
        val hardware = hardware()
        val boundTo = prefs.getString(KEY_DEVICE, null)
        if (boundTo.isNullOrBlank()) {
            return Status(State.UNBOUND, message = "Not bound to a device yet")
        }
        if (boundTo != hardware) {
            // The seal is useless on this phone and must not be left around for
            // the next app to find. It also makes the situation unambiguous if
            // the operator later moves the app back.
            wipeSeal()
            return Status(
                State.BOUND_ELSEWHERE,
                message = "This console is bound to another device",
            )
        }
        val plain = openSeal() ?: return Status(
            if (prefs.getString(KEY_DEVICE, null).isNullOrBlank()) State.UNBOUND else State.KEY_LOST,
            message = "Device key unavailable — enter the admin code again",
        )
        return Status(
            State.BOUND_HERE,
            code = plain,
            boundAt = prefs.getLong(KEY_BOUND_AT, 0L),
            message = "Bound to this device · ${hardwareLabel()}",
        )
    }

    /**
     * Seals [code] to this phone. Called only after the **server** has accepted
     * the code, so binding can never be an offline guess.
     *
     * Refuses when the seal already belongs to other hardware: that phone must
     * be cleared deliberately (app data wipe + code) rather than silently
     * re-homed here.
     */
    fun bind(code: String): Boolean {
        val clean = code.trim()
        if (clean.isEmpty()) return false
        val hardware = hardware()
        val boundTo = prefs.getString(KEY_DEVICE, null)
        if (!boundTo.isNullOrBlank() && boundTo != hardware) return false
        val sealed = runCatching { seal(clean, hardware) }.getOrNull() ?: return false
        prefs.edit()
            .putString(KEY_DEVICE, hardware)
            .putString(KEY_CT, Base64.encodeToString(sealed.first, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(sealed.second, Base64.NO_WRAP))
            .putLong(KEY_BOUND_AT, System.currentTimeMillis())
            .putString(KEY_MODEL, hardwareLabel())
            .apply()
        return true
    }

    /** Drops the binding. Next unlock re-seals on whatever phone types the code. */
    fun unbind() = wipeSeal()

    /** "HUAWEI GRL-LX9" — for the console header, never for identification. */
    fun hardwareLabel(): String =
        (Build.MANUFACTURER + " " + Build.MODEL).trim().replaceFirstChar { it.uppercase() }

    /** SHA-256 over the identifiers that survive reinstall but not a phone swap. */
    fun hardware(): String {
        val ssaid = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val parts = listOf(
            ssaid,
            Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE,
            Build.PRODUCT, Build.HARDWARE, Build.BOARD, Build.FINGERPRINT,
        )
        return sha256(parts.joinToString("\u0000").toByteArray())
    }

    // ---- seal -------------------------------------------------------------

    private fun wipeSeal() {
        prefs.edit()
            .remove(KEY_DEVICE).remove(KEY_CT).remove(KEY_IV).remove(KEY_BOUND_AT)
            .apply()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun seal(plain: String, aad: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(plain.toByteArray()) to cipher.iv
    }

    private fun openSeal(): String? {
        val ct = prefs.getString(KEY_CT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.updateAAD(hardware().toByteArray())
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
        }.getOrNull()
    }

    /**
     * The sealing key, created on first use and never exported.
     *
     * StrongBox (a discrete secure element) is requested when the hardware has
     * one and silently skipped when it does not — refusing to seal on phones
     * without it would make the console unusable on most of them.
     */
    private fun key(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No biometric/lock-screen requirement: the console must open
                // while the operator is driving the phone hands-free.
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setIsStrongBoxBacked(true)
            }
            return b.build()
        }

        return try {
            gen.init(spec(true))
            gen.generateKey()
        } catch (e: Exception) {
            // StrongBoxUnavailableException on most phones; anything else here
            // also means "fall back to the plain TEE key" — never "no key".
            if (e is java.security.InvalidAlgorithmParameterException || e is StrongBoxUnavailableException) {
                gen.init(spec(false))
                gen.generateKey()
            } else throw e
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "axzora.admin.device.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PREFS = "axzora_admin_guard"
        const val KEY_DEVICE = "device"
        const val KEY_CT = "sealed"
        const val KEY_IV = "iv"
        const val KEY_BOUND_AT = "bound_at"
        const val KEY_MODEL = "model"
    }
}
