package com.axzora.os.soundmesh

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SoundMesh — an offline, device-to-device acoustic link.
 *
 * This is the foundational codec: text is AES-256 encrypted, the bytes are sent
 * as a stream of two-tone (2-FSK) acoustic bursts through the speaker, and the
 * receiving phone records the microphone and decodes the tones back (Goertzel
 * filter per burst — no FFT library needed). No internet, no radio, no bluetooth.
 *
 * Honest scope: this is a working foundation, not the full "SoundMesh X" vision.
 * The AI noise separator, OFDM, ultrasonic mode, self-healing packets and mesh
 * networking from that design are future R&D. What this gives you is the real
 * "send encrypted text through sound" primitive, which is the heart of it.
 *
 * Global AES-256 key/IV here are placeholders for the real per-session key
 * exchange (Curve25519) described in the product spec — used so the payload is
 * genuinely encrypted, not just encoded.
 */
object SoundMesh {

    const val SAMPLE_RATE = 22050
    const val BIT_1_HZ = 1500f
    const val BIT_0_HZ = 1050f
    const val BIT_MS = 30                    // ms per bit (~33 bit/s); 40ms was too slow
    const val LOCK_HZ = 1800f                // preamble/lock tone
    private const val PREAMBLE = 10          // leading lock windows for sync
    private const val BIT_REPEAT = 3         // FEC: each bit sent 3x, majority vote

    // Demo AES-256 key + IV (32 / 16 bytes). Replace with a per-session key for
    // real security; kept here so the payload is encrypted end-to-end.
    private val KEY = "smx-demo-key-0001-0123456789abcdef0000".toByteArray().sliceArray(0..31)
    private val IV = "smx-demo-iv-01234".toByteArray().sliceArray(0..15)

    private fun tone(freq: Float, ms: Int): ShortArray {
        val n = (SAMPLE_RATE * ms / 1000).toInt()
        val amp = 0.6 * 32767
        // Exactly n samples per tone (no padding): the decoder counts one window
        // (win) per tone, so any extra samples would drift the window boundaries
        // and corrupt the bits. This is what made SoundMesh fail to decode.
        return ShortArray(n) { i ->
            (amp * sin(2.0 * PI * freq * i / SAMPLE_RATE)).toInt().toShort()
        }
    }

    /** Encode text into a 16-bit mono PCM stream (header + FEC + CRC + AES). */
    fun encodeToPcm(text: String): ShortArray {
        val raw = text.toByteArray()
        val enc = encrypt(raw)
        // Header(1B len) + ciphertext + CRC32 -> then 3x-repeated bits (FSK).
        val payload = byteArrayOf(enc.size.toByte()) + enc + crc32(enc)
        val out = ArrayList<Short>(payload.size * 8 * BIT_REPEAT * (SAMPLE_RATE * BIT_MS / 1000))
        repeat(PREAMBLE) { out.addAll(tone(LOCK_HZ, BIT_MS).toList()) }
        for (b in payload) {
            for (i in 0 until 8) {
                val bit = ((b.toInt() shr (7 - i)) and 1) == 1
                val t = tone(if (bit) BIT_1_HZ else BIT_0_HZ, BIT_MS)
                repeat(BIT_REPEAT) { out.addAll(t.toList()) }
            }
        }
        return out.toShortArray()
    }

    /** Decode a PCM stream back to text. FEC majority vote + CRC integrity. */
    fun decodeFromPcm(samples: ShortArray): String? {
        val win = SAMPLE_RATE * BIT_MS / 1000
        val nwin = samples.size / win
        if (nwin < PREAMBLE + 8) return null

        // SYNC: the sender leads with PREAMBLE lock(1800Hz) windows. Real mic
        // capture has latency and a little leading silence, so the buffer almost
        // never starts on the preamble. Find the run of windows where the lock
        // tone dominates (the preamble), then decode the data that follows it.
        var bestStart = -1; var bestLen = 0; var bestEnergy = -1e9
        var runStart = -1; var runLen = 0; var runMax = -1e9
        fun closeRun() {
            if (runLen >= 3 && runMax > bestEnergy) {
                bestEnergy = runMax; bestStart = runStart; bestLen = runLen
            }
            runStart = -1; runLen = 0; runMax = -1e9
        }
        for (i in 0 until nwin) {
            val st = i * win
            val pl = goertzel(samples, st, win, LOCK_HZ)
            val p1 = goertzel(samples, st, win, BIT_1_HZ)
            val p0 = goertzel(samples, st, win, BIT_0_HZ)
            if (pl > p1 && pl > p0) {
                if (runStart < 0) runStart = i
                runLen++
                if (pl > runMax) runMax = pl
            } else closeRun()
        }
        closeRun()
        if (bestStart < 0) return null
        // Data begins after the PREAMBLE lock windows.
        val dataStart = bestStart + PREAMBLE
        if (dataStart + BIT_REPEAT > nwin) return null

        // Majority-vote each group of BIT_REPEAT windows into one bit.
        val bits = ArrayList<Int>(nwin / BIT_REPEAT)
        var w = dataStart
        while (w + BIT_REPEAT <= nwin) {
            var ones = 0
            for (k in 0 until BIT_REPEAT) {
                val start = (w + k) * win
                val p1 = goertzel(samples, start, win, BIT_1_HZ)
                val p0 = goertzel(samples, start, win, BIT_0_HZ)
                if (p1 > p0) ones++
            }
            bits.add(if (ones >= BIT_REPEAT / 2 + 1) 1 else 0)
            w += BIT_REPEAT
        }
        val bytes = mutableListOf<Byte>()
        var cur = 0
        var n = 0
        for (b in bits) {
            cur = (cur shl 1) or b
            n++
            if (n == 8) { bytes.add(cur.toByte()); cur = 0; n = 0 }
        }
        if (bytes.size < 5) return null
        val len = bytes[0].toInt() and 0xFF
        if (len <= 0 || len >= bytes.size - 4) return null
        val body = bytes.subList(1, 1 + len).toByteArray()
        val crc = bytes.subList(1 + len, 1 + len + 4).toByteArray()
        if (len != body.size || !crc32(body).contentEquals(crc)) return null
        return runCatching { String(decrypt(body)) }.getOrNull()
    }

    private fun crc32(data: ByteArray): ByteArray {
        val c = java.util.zip.CRC32()
        c.update(data)
        val v = c.value
        return byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    /** Goertzel power for one target frequency in a window (fixed 2c terms). */
    private fun goertzel(samples: ShortArray, start: Int, len: Int, freq: Float): Double {
        val k = (0.5 + len.toDouble() * freq / SAMPLE_RATE).toInt()
        val w = 2.0 * PI * k / len
        val c = cos(w); val s = sin(w)
        var q1 = 0.0; var q2 = 0.0
        for (i in start until start + len) {
            val x = samples[i] / 32768.0
            val q0 = 2.0 * c * q1 - q2 + x
            q2 = q1; q1 = q0
        }
        return q1 * q1 + q2 * q2 - 2.0 * c * q1 * q2
    }

    // ---- AES-256 (CTR, no padding -> ciphertext length == plaintext length,
    //      so short messages stay short and transmit fast & reliably) ---------

    private fun encrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return c.doFinal(data)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return c.doFinal(data)
    }
}
