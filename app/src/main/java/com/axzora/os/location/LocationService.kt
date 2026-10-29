package com.axzora.os.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.axzora.os.Graph
import com.axzora.os.MainActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that periodically publishes this device's location to the
 * shared [FamilySync] while opt-in sharing is enabled. Runs with a persistent
 * notification so the user always knows sharing is active.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var familyCode: String = ""
    private var displayName: String = ""

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            scope.launch {
                Graph.familySync.publish(familyCode, displayName, loc.latitude, loc.longitude)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // started only after permission granted
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        familyCode = intent?.getStringExtra(EXTRA_CODE).orEmpty()
        displayName = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        // Re-apply config: the service can outlive the UI (or be restarted by
        // the system with START_STICKY), so it must not rely on Graph already
        // being configured by the Activity.
        Graph.configure(
            serverUrl = intent?.getStringExtra(EXTRA_SERVER).orEmpty(),
            deviceId = intent?.getStringExtra(EXTRA_DEVICE).orEmpty(),
        )

        startForeground(NOTIF_ID, buildNotification())

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .build()
        runCatching {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
        // REDELIVER (not STICKY) so a system restart hands the original intent
        // back with the server URL / device id extras still attached.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        runCatching { client.removeLocationUpdates(callback) }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            // Ember accent, so the notification shade matches the theme.
            .setColor(0xFFF0A73C.toInt())
            .setContentTitle("Axzora location sharing on")
            .setContentText("Your location is shared with your family group.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "axzora_location"
        private const val NOTIF_ID = 42
        const val EXTRA_CODE = "family_code"
        const val EXTRA_NAME = "display_name"
        const val EXTRA_SERVER = "server_url"
        const val EXTRA_DEVICE = "device_id"

        fun start(
            context: Context,
            familyCode: String,
            displayName: String,
            serverUrl: String,
            deviceId: String,
        ) {
            val i = Intent(context, LocationService::class.java)
                .putExtra(EXTRA_CODE, familyCode)
                .putExtra(EXTRA_NAME, displayName)
                .putExtra(EXTRA_SERVER, serverUrl)
                .putExtra(EXTRA_DEVICE, deviceId)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }
    }
}
