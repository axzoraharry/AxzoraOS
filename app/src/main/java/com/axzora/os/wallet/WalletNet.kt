package com.axzora.os.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Another Axzora user, discovered from the directory. */
data class Person(
    val uid: String,
    val name: String,
    val phone: String,
    val seen: Long,
) {
    /** Active in the last five minutes. */
    val online get() = System.currentTimeMillis() - seen < 300_000
}

/** A payment queued on the server, waiting to be claimed. */
data class Incoming(
    val id: Long,
    val fromName: String,
    val hp: Double,
    val note: String,
    val ts: Long,
)

sealed interface SendResult {
    data class Ok(val toName: String) : SendResult
    data object NoSuchId : SendResult
    data class Failed(val reason: String) : SendResult
}

/** Talks to the Happy Paisa directory and transfer queue on the server. */
class WalletNet(private val baseUrl: () -> String) {

    private fun url(path: String) = URL("${baseUrl().trimEnd('/')}$path")

    private fun HttpURLConnection.setup(method: String, body: JSONObject? = null) {
        requestMethod = method
        connectTimeout = 6000
        readTimeout = 8000
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toString().toByteArray()) }
        }
    }

    /** Publishes this device's ID so others can find and pay it. */
    suspend fun register(uid: String, name: String, phone: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = url("/wallet/register").openConnection() as HttpURLConnection
                c.setup(
                    "POST",
                    JSONObject().put("uid", uid).put("name", name).put("phone", phone),
                )
                c.responseCode == 200
            }.getOrDefault(false)
        }

    /** Everyone else running Axzora, most recently active first. */
    suspend fun directory(myUid: String): List<Person> = withContext(Dispatchers.IO) {
        runCatching {
            val c = url("/wallet/directory?uid=" + URLEncoder.encode(myUid, "UTF-8"))
                .openConnection() as HttpURLConnection
            c.setup("GET")
            val arr = JSONObject(c.inputStream.bufferedReader().readText())
                .getJSONArray("people")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Person(
                    uid = o.getString("uid"),
                    name = o.optString("name", "Axzora user"),
                    phone = o.optString("phone"),
                    seen = o.optLong("seen"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Returns the display name behind an ID, or null when unknown. */
    suspend fun lookup(uid: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = url("/wallet/lookup?uid=" + URLEncoder.encode(uid, "UTF-8"))
                .openConnection() as HttpURLConnection
            c.setup("GET")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            if (o.optBoolean("found")) o.optString("name", "Axzora user") else null
        }.getOrNull()
    }

    suspend fun send(
        fromUid: String, fromName: String, toUid: String, hp: Double, note: String,
    ): SendResult = withContext(Dispatchers.IO) {
        runCatching {
            val c = url("/wallet/send").openConnection() as HttpURLConnection
            c.setup(
                "POST",
                JSONObject()
                    .put("from", fromUid).put("fromName", fromName)
                    .put("to", toUid).put("hp", hp).put("note", note),
            )
            when (c.responseCode) {
                200 -> {
                    val o = JSONObject(c.inputStream.bufferedReader().readText())
                    SendResult.Ok(o.optString("toName", toUid))
                }
                404 -> SendResult.NoSuchId
                else -> SendResult.Failed("server said ${c.responseCode}")
            }
        }.getOrElse { SendResult.Failed(it.message ?: "network error") }
    }

    suspend fun inbox(uid: String): List<Incoming> = withContext(Dispatchers.IO) {
        runCatching {
            val c = url("/wallet/inbox?uid=" + URLEncoder.encode(uid, "UTF-8"))
                .openConnection() as HttpURLConnection
            c.setup("GET")
            val arr: JSONArray = JSONObject(c.inputStream.bufferedReader().readText())
                .getJSONArray("transfers")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Incoming(
                    id = o.getLong("id"),
                    fromName = o.optString("fromName", "Someone"),
                    hp = o.optDouble("hp", 0.0),
                    note = o.optString("note"),
                    ts = o.optLong("ts"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Confirms delivery so the server drops these from the queue. */
    suspend fun ack(uid: String, ids: List<Long>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = url("/wallet/ack").openConnection() as HttpURLConnection
            c.setup("POST", JSONObject().put("uid", uid).put("ids", JSONArray(ids)))
            c.responseCode == 200
        }.getOrDefault(false)
    }
}
