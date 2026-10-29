package com.axzora.os.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One Axzora user, as the admin console sees them. */
data class AdminUser(
    val uid: String,
    val name: String,
    val phone: String,
    val seen: Long,
    val balanceHp: Double,
    val pendingNotices: Int,
    val pendingTransfers: Int,
)

data class AdminTotals(
    val users: Int,
    val circles: Int,
    val happyPaisa: Double,
    val ledgerEntries: Int,
)

data class AdminOverview(
    val people: List<AdminUser>,
    val totals: AdminTotals,
)

/** An announcement sent by the Axzora admin. */
data class Notice(
    val id: Long,
    val title: String,
    val text: String,
    val ts: Long,
)


/** Why a claim on the admin console failed. */
sealed interface ClaimResult {
    data object Ok : ClaimResult
    data object WrongCode : ClaimResult
    data class HeldElsewhere(val holder: String) : ClaimResult
    data class Failed(val reason: String) : ClaimResult
}

sealed interface NotifyResult {
    data class Sent(val delivered: Int) : NotifyResult
    data object NotAdmin : NotifyResult
    data object NoSuchId : NotifyResult
    data class Failed(val reason: String) : NotifyResult
}

/**
 * Admin announcements.
 *
 * Sending is authorised by the server on every request — the admin code is
 * never trusted from the app alone, since anything shipped inside an APK can
 * be read by whoever unpacks it.
 */
class AdminNotify(private val baseUrl: () -> String) {

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

    /**
     * Takes the admin console for this device.
     *
     * The server allows one holder at a time, so a correct code is necessary
     * but not sufficient — if another phone already has it, this fails rather
     * than silently taking over.
     */
    suspend fun claim(
        adminCode: String,
        deviceId: String,
        name: String,
    ): ClaimResult = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/admin/claim", "POST",
                JSONObject()
                    .put("adminCode", adminCode)
                    .put("deviceId", deviceId)
                    .put("name", name),
            )
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val o = JSONObject(stream?.bufferedReader()?.readText().orEmpty().ifBlank { "{}" })
            when (c.responseCode) {
                200 -> ClaimResult.Ok
                403 -> ClaimResult.WrongCode
                409 -> ClaimResult.HeldElsewhere(o.optString("holder", "another device"))
                else -> ClaimResult.Failed(o.optString("error", "server said ${c.responseCode}"))
            }
        }.getOrElse { ClaimResult.Failed(it.message ?: "network error") }
    }

    /** Hands the console back so another device can take it. */
    suspend fun release(adminCode: String, deviceId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(
                    "/admin/release", "POST",
                    JSONObject().put("adminCode", adminCode).put("deviceId", deviceId),
                )
                c.responseCode == 200
            }.getOrDefault(false)
        }

    /** @param target an Axzora ID, or "ALL" for everyone using the app. */
    suspend fun send(
        adminCode: String,
        deviceId: String,
        title: String,
        text: String,
        target: String,
    ): NotifyResult = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/admin/notify", "POST",
                JSONObject()
                    .put("adminCode", adminCode)
                    .put("deviceId", deviceId)
                    .put("title", title)
                    .put("text", text)
                    .put("target", target),
            )
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val o = JSONObject(stream.bufferedReader().readText())
            when (c.responseCode) {
                200 -> NotifyResult.Sent(o.optInt("delivered", 0))
                // 409 means the console is held elsewhere, which is a
                // different problem from a wrong code and reads differently.
                409 -> NotifyResult.Failed(o.optString("error", "admin is active on another device"))
                403 -> NotifyResult.NotAdmin
                404 -> NotifyResult.NoSuchId
                else -> NotifyResult.Failed(o.optString("error", "server said ${c.responseCode}"))
            }
        }.getOrElse { NotifyResult.Failed(it.message ?: "network error") }
    }

    /**
     * Everyone on the system with balances and queued items.
     * Null means the server refused the code or could not be reached, which is
     * also what gates the admin screen.
     */
    /**
     * Sets (or clears, with a blank url) the TURN relay the server hands to
     * phones for calls across networks. Returns "ok" or a reason.
     */
    suspend fun setRelay(adminCode: String, deviceId: String, url: String, user: String, pass: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(
                    "/admin/ice", "POST",
                    JSONObject().put("adminCode", adminCode).put("deviceId", deviceId)
                        .put("url", url).put("username", user).put("credential", pass),
                )
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val o = JSONObject(stream?.bufferedReader()?.readText().orEmpty().ifBlank { "{}" })
                if (c.responseCode == 200) (if (o.optBoolean("relay")) "ok" else "cleared")
                else o.optString("error", "HTTP " + c.responseCode)
            }.getOrElse { "Server unreachable: " + (it.message ?: "") }
        }

    suspend fun overview(adminCode: String, deviceId: String): AdminOverview? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/admin/overview?adminCode=" + URLEncoder.encode(adminCode, "UTF-8") +
                    "&deviceId=" + URLEncoder.encode(deviceId, "UTF-8"),
                "GET",
            )
            if (c.responseCode != 200) return@runCatching null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = o.getJSONArray("people")
            val people = (0 until arr.length()).map { i ->
                val u = arr.getJSONObject(i)
                AdminUser(
                    uid = u.getString("uid"),
                    name = u.optString("name", "Axzora user"),
                    phone = u.optString("phone"),
                    seen = u.optLong("seen"),
                    balanceHp = u.optDouble("balanceHp", 0.0),
                    pendingNotices = u.optInt("pendingNotices"),
                    pendingTransfers = u.optInt("pendingTransfers"),
                )
            }
            val t = o.getJSONObject("totals")
            AdminOverview(
                people = people,
                totals = AdminTotals(
                    users = t.optInt("users"),
                    circles = t.optInt("circles"),
                    happyPaisa = t.optDouble("happyPaisa", 0.0),
                    ledgerEntries = t.optInt("ledgerEntries"),
                ),
            )
        }.getOrNull()
    }

    /** Announcements waiting for this device, newer than [sinceId]. */
    suspend fun poll(uid: String, sinceId: Long): List<Notice> = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/notify/poll?uid=" + URLEncoder.encode(uid, "UTF-8") + "&since=$sinceId",
                "GET",
            )
            val arr = JSONObject(c.inputStream.bufferedReader().readText())
                .getJSONArray("notices")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Notice(
                    id = o.getLong("id"),
                    title = o.optString("title", "Axzora"),
                    text = o.optString("text"),
                    ts = o.optLong("ts"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
