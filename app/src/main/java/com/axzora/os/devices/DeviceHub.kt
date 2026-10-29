package com.axzora.os.devices

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One Axzora install, as the device hub sees it. */
data class Device(
    val deviceId: String,
    val name: String,
    val model: String,
    val battery: Int,
    val charging: Boolean,
    val seen: Long,
    val online: Boolean,
)

/**
 * Every Axzora device this account owns, live.
 *
 * The heartbeat is the whole mechanism: each install tells the server it's
 * alive every half-minute, carrying a snapshot of its state, and in return
 * learns whether it's been asked to ring itself. Because presence is keyed by
 * the install's stable id rather than the account, a phone and a tablet both
 * show up — which is what "all my devices" has to mean.
 *
 * Nothing here reaches across to another device directly. A device only ever
 * acts on a request the server hands it about *itself*, so one phone can never
 * be made to do something by another.
 */
class DeviceHub(
    private val context: Context,
    private val baseUrl: () -> String,
) {

    /** This phone's own stable id, so it can exclude itself from the list. */
    var deviceId: String = ""

    /**
     * Announce this device and pick up any pending ring request.
     * @return true if the server asked this device to ring.
     */
    suspend fun heartbeat(uid: String, name: String): Boolean = withContext(Dispatchers.IO) {
        if (deviceId.isBlank() || uid.isBlank()) return@withContext false
        runCatching {
            val body = JSONObject()
                .put("deviceId", deviceId)
                .put("uid", uid)
                .put("name", name.ifBlank { Build.MODEL ?: "My device" })
                .put("model", Build.MODEL ?: "")
                .put("battery", batteryPercent())
                .put("charging", isCharging())

            val c = open("/devices/heartbeat", "POST", body)
            if (c.responseCode != 200) return@withContext false
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            c.disconnect()
            o.optBoolean("ring")
        }.getOrDefault(false)
    }

    suspend fun list(uid: String): List<Device> = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext emptyList()
        runCatching {
            val c = open("/devices/list?uid=$uid", "GET")
            if (c.responseCode != 200) return@withContext emptyList()
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("devices")
            c.disconnect()
            if (arr == null) return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Device(
                    deviceId = o.optString("deviceId"),
                    name = o.optString("name"),
                    model = o.optString("model"),
                    battery = o.optInt("battery", -1),
                    charging = o.optBoolean("charging"),
                    seen = o.optLong("seen"),
                    online = o.optBoolean("online"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun ring(uid: String, targetDeviceId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/devices/ring", "POST",
                JSONObject().put("uid", uid).put("deviceId", targetDeviceId),
            )
            c.responseCode == 200
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ bits

    private fun open(path: String, method: String, body: JSONObject? = null): HttpURLConnection {
        val c = URL("${baseUrl().trimEnd('/')}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 10000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return c
    }

    private fun batteryPercent(): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }.getOrDefault(-1)

    private fun isCharging(): Boolean = runCatching {
        val s = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)
}
