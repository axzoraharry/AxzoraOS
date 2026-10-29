package com.axzora.os.openroom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.axzora.os.MainActivity
import kotlin.concurrent.thread
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keep-Alive — stop the OpenRoom/backend servers from dying.
 *
 * The servers run in a shell process that Android suspends the moment the
 * device idles (Doze), which is why OpenRoom kept "going down". This foreground
 * service holds a partial wake-lock so the device never deep-sleeps while it's
 * on, and it checks the servers each loop, bringing them back if they drop.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(ID, buildNotification())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "axzora:keepalive").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun loop() {
        val home = OpenRoom.shellHome(this)
        var ticks = 0
        while (running) {
            try {
                // The whole-stack logic lives in a shell script on purpose: it can
                // be fixed and re-tested without a multi-minute APK build, and it
                // is the same code path a human would run by hand.
                //
                // This service is the only supervisor that survives Android
                // reclaiming the shell app's process group, so it must not limit
                // itself to two ports the way it used to (:3000 and :8787 only).
                // axzora-ensure.sh covers all 17 services AND restarts the keeper
                // and watchdog when THEY die — the case that left 15 services
                // unsupervised and permanently down.
                //
                // Fast path: the moments the core is down we re-check immediately;
                // otherwise a full sweep every ~30s.
                val coreDown = !isUp(3000) || !isUp(8787)
                if (coreDown || ticks % 3 == 0) {
                    OpenRoom.runShell(this, "bash axzora-ensure.sh", home ?: "/")
                }
            } catch (_: Throwable) { }
            ticks++
            try { Thread.sleep(10000) } catch (_: InterruptedException) { break }
        }
    }

    private fun isUp(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1500) }
        true
    } catch (_: Throwable) { false }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL, "AxzoraOS servers", NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
        b.setContentTitle("AxzoraOS servers")
        b.setContentText("Keeping OpenRoom & the backend online")
        b.setSmallIcon(android.R.drawable.ic_menu_view)
        b.setOngoing(true)
        b.setContentIntent(pi)
        return b.build()
    }

    companion object {
        private const val ID = 9001
        private const val CHANNEL = "axzora_keepalive"

        fun start(context: Context) {
            try {
                val i = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
                else context.startService(i)
            } catch (_: Throwable) { }
        }
    }
}
