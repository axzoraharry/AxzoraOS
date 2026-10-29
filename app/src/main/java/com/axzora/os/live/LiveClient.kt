package com.axzora.os.live

import com.axzora.os.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One live room a host is broadcasting, as seen by the /live/list endpoint. */
data class LiveRoom(
    val roomId: String,
    val title: String,
    val host: String,
    val hostName: String,
    val viewers: Int,
)

/**
 * Talks to the backend's live-room directory. The actual audio/video is the
 * ordinary WebRTC group-call machinery (the server "room" is its guest list);
 * this only answers "who is live right now?" so Axzora installs can find a
 * broadcast to join, and can tip the host once they are watching.
 */
class LiveClient(private val serverUrl: () -> String) {

    /** The live rooms currently being broadcast, newest/changed first. */
    suspend fun list(): List<LiveRoom> = withContext(Dispatchers.IO) {
        runCatching {
            val base = serverUrl().trim().ifBlank { Config.DEFAULT_SERVER }.trimEnd('/')
            val c = URL("$base/live/list").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            if (c.responseCode != 200) return@withContext emptyList()
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).optJSONArray("rooms") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                LiveRoom(
                    roomId = o.optString("roomId"),
                    title = o.optString("title", "Live"),
                    host = o.optString("host"),
                    hostName = o.optString("hostName", o.optString("host")),
                    viewers = o.optInt("viewers", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Quick liveness probe for a room, so stale entries can be dropped. */
    suspend fun probe(base: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("${base.trimEnd('/')}/health").openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.responseCode == 200
        }.getOrDefault(false)
    }
}
