package com.axzora.os.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A plain voice pipeline for the paths WebRTC can't take: the microphone is
 * read in 20 ms frames, squeezed to 8-bit µ-law (16 kHz mono → 16 KB/s), and
 * handed to a transport; frames that come back are played straight out.
 *
 * Used by the server relay (when two phones have no direct route) and by the
 * Nearby (Bluetooth / Wi-Fi Direct) call. The phone's own echo canceller,
 * noise suppressor and gain control are attached when the hardware has them.
 */
class PcmAudio(private val send: (ByteArray) -> Unit) {

    companion object {
        const val RATE = 16_000
        const val FRAME_SAMPLES = 320          // 20 ms
        private const val MAX_QUEUED_FRAMES = 15   // ~300 ms of jitter buffer, then drop

        // ---- G.711 µ-law ----
        private const val BIAS = 0x84
        private const val CLIP = 32635
        fun encode(sample: Int): Byte {
            var s = sample
            val sign = if (s < 0) { s = -s; 0x80 } else 0
            if (s > CLIP) s = CLIP
            s += BIAS
            var exp = 7
            var mask = 0x4000
            while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
            val mant = (s shr (exp + 3)) and 0x0F
            return ((sign or (exp shl 4) or mant).inv() and 0xFF).toByte()
        }
        fun decode(b: Byte): Int {
            val u = b.toInt().inv() and 0xFF
            val sign = u and 0x80
            val exp = (u shr 4) and 0x07
            val mant = u and 0x0F
            var s = ((mant shl 3) + BIAS) shl exp
            s -= BIAS
            return if (sign != 0) -s else s
        }
    }

    private val running = AtomicBoolean(false)
    @Volatile var muted = false
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    @Volatile var framesIn = 0L
    @Volatile var framesOut = 0L

    /** Queue a received µ-law frame for playback. */
    fun feed(frame: ByteArray) {
        if (!running.get()) return
        if (playQueue.size >= MAX_QUEUED_FRAMES) playQueue.poll()   // stay live, not late
        playQueue.offer(frame)
        framesIn++
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRec, FRAME_SAMPLES * 2 * 4),
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { running.set(false); return false }
        recorder = rec
        runCatching { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }
        runCatching { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }
        runCatching { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } }

        val minPlay = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            maxOf(minPlay, FRAME_SAMPLES * 2 * 6), AudioTrack.MODE_STREAM, rec.audioSessionId,
        )
        track = tr
        runCatching { tr.play() }
        runCatching { rec.startRecording() }

        thread(name = "pcm-capture", isDaemon = true) {
            val pcm = ShortArray(FRAME_SAMPLES)
            val out = ByteArray(FRAME_SAMPLES)
            while (running.get()) {
                val n = runCatching { rec.read(pcm, 0, FRAME_SAMPLES) }.getOrDefault(-1)
                if (n <= 0) { Thread.sleep(5); continue }
                if (muted) continue
                for (i in 0 until n) out[i] = encode(pcm[i].toInt())
                runCatching { send(if (n == FRAME_SAMPLES) out.copyOf() else out.copyOf(n)) }
                framesOut++
            }
        }
        thread(name = "pcm-play", isDaemon = true) {
            val pcm = ShortArray(FRAME_SAMPLES)
            while (running.get()) {
                val f = playQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val n = minOf(f.size, FRAME_SAMPLES)
                for (i in 0 until n) pcm[i] = decode(f[i]).coerceIn(-32768, 32767).toShort()
                runCatching { tr.write(pcm, 0, n) }
            }
        }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        playQueue.clear()
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        runCatching { aec?.release() }; runCatching { ns?.release() }; runCatching { agc?.release() }
        aec = null; ns = null; agc = null
    }
}
