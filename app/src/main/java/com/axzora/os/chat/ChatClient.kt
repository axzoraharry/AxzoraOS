package com.axzora.os.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One decrypted chat message. */
data class ChatMessage(
    val id: Long,
    val from: String,
    val text: String,
    val ts: Long,
    val mine: Boolean,
)

/**
 * Talks to the family-chat relay. Encrypts before send, decrypts after poll —
 * the server only ever stores ciphertext (see CryptoBox).
 */
class ChatClient {

    suspend fun send(server: String, code: String, from: String, text: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val (cipher, iv) = CryptoBox.seal(code, text)
                val conn = URL("${server.trimEnd('/')}/chat/send").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("code", code).put("from", from)
                    .put("cipher", cipher).put("iv", iv)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode == 200
            }.getOrDefault(false)
        }

    suspend fun poll(server: String, code: String, sinceId: Long, myName: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val u = "${server.trimEnd('/')}/chat/poll?code=${URLEncoder.encode(code, "UTF-8")}&since=$sinceId"
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val arr = JSONObject(conn.inputStream.bufferedReader().readText())
                    .getJSONArray("messages")
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val text = CryptoBox.open(code, o.getString("cipher"), o.getString("iv"))
                        ?: return@mapNotNull null // different code or tampered — drop
                    ChatMessage(
                        id = o.getLong("id"),
                        from = o.getString("from"),
                        text = text,
                        ts = o.getLong("ts"),
                        mine = o.getString("from") == myName,
                    )
                }
            }.getOrDefault(emptyList())
        }
}
