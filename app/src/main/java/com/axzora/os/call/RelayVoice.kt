package com.axzora.os.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The call path of last resort: audio relayed through the Axzora server over
 * a WebSocket. Nothing to configure, works from any network that can reach
 * the server at all — the trade is a little more delay than a direct call,
 * and voice only.
 *
 * Both phones open /voice?call=<id>; the server forwards each phone's frames
 * to the other. The socket is re-dialled if it drops mid-call.
 */
class RelayVoice(
    private val wsUrl: String,
    private val callId: String,
    private val uid: String,
    private val scope: CoroutineScope,
    private val onPeer: (connected: Boolean) -> Unit = {},
    private val onDead: (String) -> Unit = {},
) {
    private var ws: WsClient? = null
    private val audio = PcmAudio { bytes -> ws?.sendBinary(bytes) }
    private val stopped = AtomicBoolean(false)
    private var keeper: Job? = null
    @Volatile var peerPresent = false
        private set

    var muted: Boolean
        get() = audio.muted
        set(v) { audio.muted = v }

    /** Opens the relay and starts the microphone. Returns false if audio can't start. */
    suspend fun start(): Boolean {
        if (!audio.start()) return false
        runCatching { connect() }          // a failed first dial is just the keeper's first retry
        keeper = scope.launch {
            var attempt = 0
            while (isActive && !stopped.get()) {
                delay(3000)
                val w = ws
                if (w == null || !w.isOpen) {
                    attempt++
                    if (attempt > 8) { onDead("Relay lost"); break }
                    runCatching { connect() }
                } else { attempt = 0; withContext(Dispatchers.IO) { w.ping() } }   // never touch the socket on Main
            }
        }
        return true
    }

    private suspend fun connect() = withContext(Dispatchers.IO) {
        if (stopped.get()) return@withContext
        runCatching { ws?.close() }
        val url = wsUrl + (if (wsUrl.contains('?')) "&" else "?") + "call=" + java.net.URLEncoder.encode(callId, "UTF-8") +
            "&uid=" + java.net.URLEncoder.encode(uid, "UTF-8")
        val c = WsClient(
            url,
            onText = { txt ->
                if (stopped.get()) return@WsClient
                runCatching {
                    val o = org.json.JSONObject(txt)
                    when (o.optString("t")) {
                        "welcome" -> { peerPresent = o.optInt("peers") > 0; onPeer(peerPresent) }
                        "peer-joined" -> { peerPresent = true; onPeer(true) }
                        "peer-left" -> { peerPresent = false; onPeer(false) }
                    }
                }
            },
            onBinary = { audio.feed(it) },
            onClose = { /* keeper re-dials */ },
        )
        c.connect()
        if (stopped.get()) { runCatching { c.close() }; return@withContext }   // stop() raced the re-dial
        ws = c
    }

    fun stop() {
        if (stopped.getAndSet(true)) return
        keeper?.cancel(); keeper = null
        audio.stop()
        val w = ws; ws = null
        scope.launch(Dispatchers.IO) { runCatching { w?.close() } }
    }

    val stats: String get() = "relay in=${audio.framesIn} out=${audio.framesOut}"
}
