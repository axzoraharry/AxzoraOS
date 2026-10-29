package com.axzora.os.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * [FamilySync] backed by the Axzora sync server (see `server/server.js`).
 *
 * The server URL is supplied per-call so the user can change it in Settings
 * without restarting anything. Uses only java.net + org.json — no extra deps.
 */
class HttpFamilySync(
    private val baseUrlProvider: () -> String,
    private val deviceIdProvider: () -> String,
) : FamilySync {

    /** Last error message, surfaced in the UI so failures aren't silent. */
    @Volatile
    var lastError: String? = null
        private set

    override suspend fun publish(familyCode: String, displayName: String, lat: Double, lon: Double) {
        val base = baseUrlProvider().trimEnd('/')
        if (base.isBlank() || familyCode.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("code", familyCode)
                    .put("deviceId", deviceIdProvider())
                    .put("name", displayName.ifBlank { "Member" })
                    .put("lat", lat)
                    .put("lon", lon)
                    .toString()
                request("$base/publish", "POST", body)
                lastError = null
            }.onFailure { lastError = friendly(it) }
        }
    }

    /**
     * Publish a vehicle onto the shared world map.
     *
     * A vehicle is the same live marker as a person, just tagged so the map
     * draws it as a car and turns it to its heading. Given a synthetic id so
     * many vehicles can share one circle without colliding with phones.
     */
    suspend fun publishVehicle(
        familyCode: String,
        vehicleId: String,
        name: String,
        lat: Double,
        lon: Double,
        heading: Float,
        speed: Float,
    ) {
        val base = baseUrlProvider().trimEnd('/')
        if (base.isBlank() || familyCode.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("code", familyCode)
                    .put("deviceId", vehicleId)
                    .put("name", name)
                    .put("lat", lat)
                    .put("lon", lon)
                    .put("kind", "vehicle")
                    .put("heading", heading.toDouble())
                    .put("speed", speed.toDouble())
                    .toString()
                request("$base/publish", "POST", body)
            }
        }
    }

    override suspend fun members(familyCode: String): List<FamilyMember> {
        val base = baseUrlProvider().trimEnd('/')
        if (base.isBlank() || familyCode.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = request("$base/members?code=${java.net.URLEncoder.encode(familyCode, "UTF-8")}", "GET", null)
                val arr = JSONObject(text).optJSONArray("members") ?: return@runCatching emptyList()
                val me = deviceIdProvider()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    FamilyMember(
                        name = o.optString("name", "Member"),
                        latitude = o.getDouble("lat"),
                        longitude = o.getDouble("lon"),
                        updatedEpochMs = o.optLong("updated", 0L),
                        isSelf = o.optString("deviceId") == me,
                        kind = o.optString("kind", "person"),
                        heading = o.optDouble("heading", 0.0).toFloat(),
                    )
                }
            }.onFailure { lastError = friendly(it) }
                .onSuccess { lastError = null }
                .getOrDefault(emptyList())
        }
    }

    /** Remove this device from the group — used when sharing is switched off. */
    suspend fun leave(familyCode: String) {
        val base = baseUrlProvider().trimEnd('/')
        if (base.isBlank() || familyCode.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("code", familyCode)
                    .put("deviceId", deviceIdProvider())
                    .toString()
                request("$base/leave", "POST", body)
            }
        }
    }

    /** Connectivity probe for the Settings screen. */
    suspend fun health(): Result<String> = withContext(Dispatchers.IO) {
        val base = baseUrlProvider().trimEnd('/')
        if (base.isBlank()) return@withContext Result.failure(IllegalStateException("No server URL set"))
        runCatching {
            val o = JSONObject(request("$base/health", "GET", null))
            "Connected — server v${o.optString("version")}, ${o.optInt("families")} group(s)"
        }.recoverCatching { throw IllegalStateException(friendly(it)) }
    }

    private fun request(urlStr: String, method: String, body: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            readTimeout = 6000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            body?.let { conn.outputStream.use { os -> os.write(it.toByteArray()) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IllegalStateException(if (msg.isNullOrBlank()) "Server returned HTTP $code" else msg)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "Server timed out — is the laptop awake and on the same Wi-Fi?"
        is java.net.ConnectException -> "Can't reach the server — check the URL and Windows Firewall."
        is java.net.UnknownHostException -> "Unknown host — check the server URL."
        else -> t.message ?: t.javaClass.simpleName
    }
}
