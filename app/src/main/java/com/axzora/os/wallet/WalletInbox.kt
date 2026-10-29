package com.axzora.os.wallet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.axzora.os.Config
import com.axzora.os.MainActivity
import com.axzora.os.R

/**
 * Claims queued payments for this device, credits the local wallet, and raises
 * a notification for each one.
 *
 * Money is credited before the server is acknowledged: if the ack fails the
 * transfer stays queued and would be re-delivered, so [WalletStore] entries are
 * keyed by transfer id and a repeat claim is ignored rather than double-paid.
 */
class WalletInbox(
    private val context: Context,
    private val wallet: WalletStore,
    private val net: WalletNet,
) {

    /** @return number of payments actually received. */
    suspend fun poll(uid: String, notify: Boolean = true): Int {
        if (uid.isBlank()) return 0
        val pending = net.inbox(uid)
        if (pending.isEmpty()) return 0

        val claimed = ArrayList<Long>()
        var received = 0
        for (t in pending) {
            val already = wallet.hasTransfer(t.id)
            if (!already) {
                wallet.creditTransfer(
                    transferId = t.id,
                    hp = t.hp,
                    party = t.fromName,
                    note = t.note,
                )
                received++
                if (notify) showNotification(t)
            }
            claimed += t.id
        }
        net.ack(uid, claimed)
        return received
    }

    private fun showNotification(t: Incoming) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Happy Paisa", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Money received" }
            )
        }
        val tap = PendingIntent.getActivity(
            context, t.id.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val rupees = "%,.0f".format(t.hp * Config.HP_TO_INR)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Received %.3f HP".format(t.hp))
            .setContentText(
                "${t.fromName} sent you ₹$rupees" +
                    if (t.note.isNotBlank()) " · ${t.note}" else ""
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_BASE + t.id.toInt(), n)
        }
    }

    companion object {
        private const val CHANNEL = "axzora_paisa"
        private const val NOTIF_BASE = 7000
    }
}
