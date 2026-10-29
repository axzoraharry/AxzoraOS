package com.axzora.os.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The sound an arriving call makes while Axzora is on screen.
 *
 * The ringing notification is deliberately suppressed when the app is
 * already open — a heads-up card on top of a full-screen call UI is just
 * noise. But suppressing the notification also suppressed the phone's
 * ringtone, so a call that arrived while the app was open was completely
 * silent: the screen changed and nothing else happened. This restores the
 * sound and the vibration that come with it.
 */
class Ringer(private val context: Context) {

    private var tone: Ringtone? = null
    private var vibrator: Vibrator? = null

    val isRinging: Boolean get() = tone?.isPlaying == true

    fun start() {
        if (isRinging) return
        // Silent and vibrate modes must be respected — an app that rings
        // through them is one people uninstall.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = am.ringerMode

        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(
                    context, RingtoneManager.TYPE_RINGTONE,
                ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                tone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }
        }

        if (mode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                        .defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                val pattern = longArrayOf(0, 800, 800)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            }
        }
    }

    fun stop() {
        runCatching { tone?.stop() }
        tone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }
}
