package com.axzora.os.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One decrypted private message. */
data class DmMessage(
    val id: Long,
    val fromUid: String,
    val fromName: String,
    val text: String,
    val ts: Long,
    val mine: Boolean,
)

/** One conversation in the private-messages list. */
data class DmThread(
    val peerUid: String,
    val peerName: String,
    val lastTs: Long,
    val unread: Int,
)

/**
 * Private one-to-one messages between Axzora IDs. Unlike the family chat —
 * where everyone with the code reads along — the server hands a thread only
 * to its two participants, and the text is sealed with a key derived from the
 * pair, so a thread never renders on anyone else's phone.
 */
class DmClient {

    /** The shared passphrase for a pair; both phones derive the same one. */
    private fun pairSecret(a: String, b: String): String =
        "axzora-dm|" + listOf(a, b).sorted().joinToString("|")

    private fun open(server: String, path: String, body: JSONObject? = null): HttpURLConnection {
        val c = URL("${server.trimEnd('/')}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return c
    }

    suspend fun send(server: String, myUid: String, myName: String, peerUid: String, text: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val (cipher, iv) = CryptoBox.seal(pairSecret(myUid, peerUid), text)
                open(
                    server, "/dm/send",
                    JSONObject().put("from", myUid).put("to", peerUid)
                        .put("fromName", myName).put("cipher", cipher).put("iv", iv),
                ).responseCode == 200
            }.getOrDefault(false)
        }

    suspend fun poll(server: String, myUid: String, peerUid: String, sinceId: Long): List<DmMessage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(
                    server,
                    "/dm/poll?uid=${URLEncoder.encode(myUid, "UTF-8")}" +
                        "&peer=${URLEncoder.encode(peerUid, "UTF-8")}&since=$sinceId",
                )
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("messages")
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val text = CryptoBox.open(pairSecret(myUid, peerUid), o.getString("cipher"), o.getString("iv"))
                        ?: return@mapNotNull null
                    DmMessage(
                        id = o.getLong("id"),
                        fromUid = o.getString("from"),
                        fromName = o.optString("fromName"),
                        text = text,
                        ts = o.getLong("ts"),
                        mine = o.getString("from") == myUid,
                    )
                }
            }.getOrDefault(emptyList())
        }

    suspend fun threads(server: String, myUid: String): List<DmThread> =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(server, "/dm/threads?uid=${URLEncoder.encode(myUid, "UTF-8")}")
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("threads")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DmThread(
                        peerUid = o.getString("peerUid"),
                        peerName = o.optString("peerName", "Axzora user"),
                        lastTs = o.optLong("lastTs"),
                        unread = o.optInt("unread"),
                    )
                }
            }.getOrDefault(emptyList())
        }

    suspend fun markRead(server: String, myUid: String, peerUid: String, upTo: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                open(server, "/dm/read", JSONObject().put("uid", myUid).put("peer", peerUid).put("upTo", upTo)).responseCode
            }
        }
    }
}
