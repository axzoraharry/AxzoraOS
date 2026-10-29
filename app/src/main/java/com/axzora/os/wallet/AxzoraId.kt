package com.axzora.os.wallet

import java.security.SecureRandom

/**
 * Every install gets one permanent, shareable Axzora ID — the handle other
 * people type to send you Happy Paisa.
 *
 * Format: AXZ-XXXX-XXXX using an alphabet with no 0/O/1/I/L, so an ID read
 * aloud or copied by hand doesn't turn into a different valid ID.
 */
object AxzoraId {

    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val rng = SecureRandom()

    fun generate(): String {
        fun block() = (1..4).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")
        return "AXZ-${block()}-${block()}"
    }

    /** Accepts loose input ("axz 7k2m 9qp4") and returns the canonical form. */
    fun normalize(raw: String): String {
        val cleaned = raw.uppercase().filter { it.isLetterOrDigit() }
        val body = cleaned.removePrefix("AXZ")
        if (body.length != 8) return raw.trim().uppercase()
        return "AXZ-${body.substring(0, 4)}-${body.substring(4, 8)}"
    }

    fun isValid(raw: String): Boolean =
        Regex("^AXZ-[$ALPHABET]{4}-[$ALPHABET]{4}$").matches(normalize(raw))
}
