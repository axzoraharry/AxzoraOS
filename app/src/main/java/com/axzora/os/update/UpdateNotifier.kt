package com.axzora.os.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Posts a system notification when a newer AxzoraOS build is available.
 *
 * The launcher process is effectively always running, so a periodic re-check
 * plus this notification means every installed phone is told about an update
 * while it's in use — not only on a cold start. Tapping it opens the launcher,
 * which shows the one-tap install dialog.
 */
object UpdateNotifier {

    private const val CHANNEL = "axzora_updates"
    private const val NOTIF_ID = 4201

    /** Show (or refresh) the "update available" notification for [available]. */
    fun show(context: Context, available: Updater.Available) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "System updates", NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Tells you when a new AxzoraOS build is ready." },
            )
        }

        // Tapping brings the launcher forward; the update dialog is already up
        // (or the next check raises it). Flag carries the offered version so the
        // launcher can jump straight to the installer.
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("axzora.show_update", true)
                putExtra("axzora.update_code", available.versionCode)
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = if (open != null)
            PendingIntent.getActivity(context, 0, open, flags) else null

        val n = Notification.Builder(
            context, if (android.os.Build.VERSION.SDK_INT >= 26) CHANNEL else "",
        )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("AxzoraOS ${available.versionName} is available")
            .setContentText(
                available.notes.ifBlank { "Tap to update — installs in a couple of taps." },
            )
            .setStyle(
                Notification.BigTextStyle().bigText(
                    available.notes.ifBlank { "Tap to update — installs in a couple of taps." },
                ),
            )
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()

        runCatching { nm.notify(NOTIF_ID, n) }
    }

    fun clear(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIF_ID) }
    }
}
