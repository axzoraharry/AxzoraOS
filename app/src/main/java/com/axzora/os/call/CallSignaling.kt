package com.axzora.os.call

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A call ringing on this device. */
data class IncomingCall(
    val callId: String,
    val from: String,
    val fromName: String,
    val sdp: String,
    /**
     * Set when this call is one leg of a group.
     *
     * If it names a room this phone is already in, the call is another
     * person joining and is answered without ringing. If it names a room
     * this phone has not joined, it is an invitation and rings.
     */
    val roomId: String = "",
)

/** Someone in a group call. */
data class RoomMember(val uid: String, val name: String)

/** A group call, as the server sees it. */
data class Room(
    val roomId: String,
    val title: String = "Group call",
    val host: String = "",
    val hostName: String = "",
    val members: List<RoomMember> = emptyList(),
    /**
     * Who this phone must dial, filled in only by a join.
     *
     * Everyone already in the room when we arrived. They will not dial us,
     * so each pair is set up exactly once and neither side can glare.
     */
    val peers: List<RoomMember> = emptyList(),
)

/** One ICE candidate as carried over the wire. */
data class IceCandidate(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String,
    /** Not a candidate at all: "I'm switching to the server relay — join me there." */
    val relay: Boolean = false,
)

data class PollResult(
    val incoming: List<IncomingCall> = emptyList(),
    val answers: Map<String, String> = emptyMap(),
    val ended: Set<String> = emptySet(),
    val ice: Map<String, List<IceCandidate>> = emptyMap(),
    /** Group calls this phone has been asked into and not yet joined. */
    val roomInvites: List<Room> = emptyList(),
)

/**
 * Carries only call *setup* between two phones — who is calling, the session
 * descriptions and the ICE candidates. Once the peers agree a route the audio
 * flows directly between them; the server never sees or relays it.
 */
data class IceServer(val url: String, val username: String, val credential: String)

class CallSignaling(private val baseUrl: () -> String) {

    /** The voice-relay WebSocket for this server (wss://…/voice). */
    fun voiceUrl(): String = baseUrl().trim().ifBlank { com.axzora.os.Config.DEFAULT_SERVER }.trimEnd('/')
        .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/voice"

    private fun open(
        path: String,
        method: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 8000,
    ): HttpURLConnection {
        val c = URL("${baseUrl().trimEnd('/')}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 6000
        c.readTimeout = readTimeoutMs
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return c
    }

    /** @return callId, or null when the invite could not be placed. */
    suspend fun invite(
        from: String,
        fromName: String,
        to: String,
        sdp: String,
        roomId: String = "",
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(
                    "/call/invite", "POST",
                    JSONObject().put("from", from).put("fromName", fromName)
                        .put("to", to).put("sdp", sdp).put("roomId", roomId),
                )
                if (c.responseCode != 200) return@runCatching null
                JSONObject(c.inputStream.bufferedReader().readText()).optString("callId")
            }.getOrNull()
        }

    suspend fun answer(callId: String, sdp: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open("/call/answer", "POST", JSONObject().put("callId", callId).put("sdp", sdp))
                .responseCode == 200
        }.getOrDefault(false)
    }

    suspend fun sendIce(callId: String, from: String, ice: IceCandidate): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val cand = JSONObject()
                    .put("sdpMid", ice.sdpMid)
                    .put("sdpMLineIndex", ice.sdpMLineIndex)
                    .put("candidate", ice.candidate)
                    .put("relay", ice.relay)
                open(
                    "/call/ice", "POST",
                    JSONObject().put("callId", callId).put("from", from).put("candidate", cand),
                ).responseCode == 200
            }.getOrDefault(false)
        }

    // ------------------------------------------------------------ group calls

    private fun readRoom(o: JSONObject): Room {
        fun members(key: String): List<RoomMember> =
            o.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).map { i ->
                    val x = arr.getJSONObject(i)
                    RoomMember(x.optString("uid"), x.optString("name", "Someone"))
                }
            } ?: emptyList()
        return Room(
            roomId = o.optString("roomId"),
            title = o.optString("title", "Group call"),
            host = o.optString("host"),
            hostName = o.optString("hostName"),
            members = members("members"),
            peers = members("peers"),
        )
    }

    /**
     * Opens a room and rings everyone invited.
     *
     * @return the new room, or null with the server's reason in [lastError].
     */
    suspend fun createRoom(
        from: String,
        fromName: String,
        invite: List<String>,
        title: String,
    ): Room? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/call/room/create", "POST",
                JSONObject().put("from", from).put("fromName", fromName)
                    .put("title", title)
                    .put("invite", JSONArray().apply { invite.forEach { put(it) } }),
            )
            if (c.responseCode != 200) { lastError = errorOf(c); return@runCatching null }
            readRoom(JSONObject(c.inputStream.bufferedReader().readText()))
        }.getOrNull()
    }

    /**
     * Opens a PUBLIC live room: no invite list and nobody is rung. The room
     * appears in /live/list and any Axzora install may join it by tapping.
     *
     * @return the new room, or null with the server's reason in [lastError].
     */
    suspend fun startLive(
        from: String,
        fromName: String,
        title: String,
    ): Room? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/live/start", "POST",
                JSONObject().put("from", from).put("fromName", fromName)
                    .put("title", title),
            )
            if (c.responseCode != 200) { lastError = errorOf(c); return@runCatching null }
            readRoom(JSONObject(c.inputStream.bufferedReader().readText()))
        }.getOrNull()
    }

    /** Joins, and comes back with the people to dial. */
    suspend fun joinRoom(roomId: String, uid: String, name: String): Room? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(
                    "/call/room/join", "POST",
                    JSONObject().put("roomId", roomId).put("uid", uid).put("name", name),
                )
                if (c.responseCode != 200) { lastError = errorOf(c); return@runCatching null }
                readRoom(JSONObject(c.inputStream.bufferedReader().readText()))
            }.getOrNull()
        }

    /**
     * Adds people to a call already under way.
     *
     * @return the room as it now stands, or null with the reason in
     *   [lastError].
     */
    suspend fun inviteToRoom(
        roomId: String,
        from: String,
        invite: List<String>,
    ): Room? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/call/room/invite", "POST",
                JSONObject().put("roomId", roomId).put("from", from)
                    .put("invite", JSONArray().apply { invite.forEach { put(it) } }),
            )
            if (c.responseCode != 200) { lastError = errorOf(c); return@runCatching null }
            readRoom(JSONObject(c.inputStream.bufferedReader().readText()))
        }.getOrNull()
    }

    suspend fun leaveRoom(roomId: String, uid: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(
                "/call/room/leave", "POST",
                JSONObject().put("roomId", roomId).put("uid", uid),
            ).responseCode == 200
        }.getOrDefault(false)
    }

    /** Why the last room call failed, in words worth showing someone. */
    @Volatile var lastError: String = ""
        private set

    private fun errorOf(c: HttpURLConnection): String = runCatching {
        JSONObject(c.errorStream?.bufferedReader()?.readText().orEmpty()).optString("error")
    }.getOrNull().orEmpty().ifBlank { "The server refused (${c.responseCode})." }

    suspend fun hangup(callId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open("/call/hangup", "POST", JSONObject().put("callId", callId)).responseCode == 200
        }.getOrDefault(false)
    }

    /**
     * @param waitMs how long the server may hold the request open when there
     *   is nothing to report. Zero asks for an immediate answer.
     *
     * Holding the request is what makes a call ring at once: every step of the
     * handshake arrives as it happens, instead of on the next three-second
     * tick. Thirty steps at up to 3s each is the difference between ringing
     * now and ringing eventually.
     */
    /**
     * ICE servers, as configured on the server.
     *
     * Fetched rather than compiled in so a relay can be swapped without
     * shipping a new APK — and so every phone agrees on the same one.
     */
    suspend fun iceServers(): List<IceServer> = withContext(Dispatchers.IO) {
        runCatching {
            val c = open("/ice", "GET")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = o.optJSONArray("iceServers") ?: return@runCatching emptyList()
            (0 until arr.length()).flatMap { i ->
                val x = arr.getJSONObject(i)
                val user = x.optString("username")
                val pass = x.optString("credential")
                val urls = x.opt("urls")
                val list = when (urls) {
                    is org.json.JSONArray ->
                        (0 until urls.length()).map { urls.getString(it) }
                    else -> listOf(urls.toString())
                }
                list.map { IceServer(it, user, pass) }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun poll(uid: String, waitMs: Int = 0): PollResult = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(
                "/call/poll?uid=" + URLEncoder.encode(uid, "UTF-8") + "&wait=" + waitMs,
                "GET",
                // Outlast the server's own hold, or the phone hangs up on a
                // request that was about to be answered.
                readTimeoutMs = waitMs + 15_000,
            )
            val o = JSONObject(c.inputStream.bufferedReader().readText())

            val incoming = o.optJSONArray("incoming")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val x = arr.getJSONObject(i)
                    IncomingCall(
                        callId = x.getString("callId"),
                        from = x.optString("from"),
                        fromName = x.optString("fromName", "Someone"),
                        sdp = x.optString("sdp"),
                        roomId = x.optString("roomId"),
                    )
                }
            } ?: emptyList()

            val answers = o.optJSONArray("answers")?.let { arr ->
                (0 until arr.length()).associate { i ->
                    val x = arr.getJSONObject(i)
                    x.getString("callId") to x.optString("sdp")
                }
            } ?: emptyMap()

            val ended = o.optJSONArray("ended")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("callId") }.toSet()
            } ?: emptySet()

            val iceObj = o.optJSONObject("ice")
            val ice = HashMap<String, List<IceCandidate>>()
            iceObj?.keys()?.forEach { key ->
                val arr: JSONArray = iceObj.getJSONArray(key)
                ice[key] = (0 until arr.length()).map { i ->
                    val x = arr.getJSONObject(i)
                    IceCandidate(
                        sdpMid = x.optString("sdpMid"),
                        sdpMLineIndex = x.optInt("sdpMLineIndex"),
                        candidate = x.optString("candidate"),
                        relay = x.optBoolean("relay", false),
                    )
                }
            }

            val roomInvites = o.optJSONArray("roomInvites")?.let { arr ->
                (0 until arr.length()).map { readRoom(arr.getJSONObject(it)) }
            } ?: emptyList()

            PollResult(incoming, answers, ended, ice, roomInvites)
        }.getOrDefault(PollResult())
    }
}
