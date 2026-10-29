package com.axzora.os.nearby

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.Uri
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.media.AudioManager
import com.axzora.os.call.PcmAudio
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/** A phone running Axzora that this device can see over radio. */
data class Peer(
    val endpointId: String,
    val name: String,
    val connected: Boolean = false,
    val connecting: Boolean = false,
)

/** One entry in the offline conversation. */
data class NearbyMessage(
    val from: String,
    val text: String,
    val mine: Boolean,
    val ts: Long = System.currentTimeMillis(),
    val fileUri: Uri? = null,
    val fileName: String? = null,
    val hp: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

/** Progress of a file being sent or received. */
data class TransferProgress(
    val name: String,
    val percent: Int,
    val incoming: Boolean,
)

/** Radio prerequisites that Nearby cannot work without. */
data class RadioState(
    val bluetoothOn: Boolean,
    val locationServicesOn: Boolean,
) {
    val ready get() = bluetoothOn && locationServicesOn
}

/**
 * Offline peer-to-peer over Google's Nearby Connections.
 *
 * Discovery runs over BLE and Bluetooth; once a connection is accepted the
 * library upgrades the link to Wi-Fi Direct, which is what makes photo and
 * video transfer usable. No internet, router or server is involved.
 *
 * Connecting is the fragile part of this API, so three things are handled
 * explicitly here:
 *
 *  - **Discovery is paused while a connection is being set up.** Scanning and
 *    handshaking compete for the same radio, and leaving discovery running is
 *    the most common reason a connection sits at "connecting" forever.
 *  - **Every attempt has a deadline.** Nearby never calls back if a handshake
 *    stalls, so without a timeout the UI would wait indefinitely with nothing
 *    to show the user.
 *  - **Simultaneous requests are resolved.** If both people tap connect at the
 *    same moment the two requests collide; the device with the lower endpoint
 *    id keeps trying and the other backs off and waits to be invited.
 */
class NearbyManager(private val context: Context) {

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val scope = CoroutineScope(SupervisorJob())

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers

    private val _messages = MutableStateFlow<List<NearbyMessage>>(emptyList())
    val messages: StateFlow<List<NearbyMessage>> = _messages

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    /**
     * Whether Google Play services (which Nearby Connections requires) is
     * usable. False means Nearby cannot scan on this phone and there is a fix
     * the user can act on (update / enable / install Play services).
     */
    private val _gmsOk = MutableStateFlow(true)
    val gmsOk: StateFlow<Boolean> = _gmsOk

    /** Human reason, and whether there is a fixable problem, for the screen. */
    data class GmsState(val ok: Boolean, val reason: String)

    private val _transfer = MutableStateFlow<TransferProgress?>(null)
    val transfer: StateFlow<TransferProgress?> = _transfer

    private var myName: String = "Axzora user"
    private var running = false
    private var discovering = false
    private var advertising = false
    private var scanRetries = 0
    private var advertiseRetries = 0

    /** Endpoints with a handshake in flight, and their timeout jobs. */
    private val pendingConnects = HashMap<String, Job>()
    /** Retry count per endpoint, so transient failures self-heal. */
    private val attempts = HashMap<String, Int>()

    private val pendingFileNames = HashMap<Long, String>()
    private val inFlight = HashMap<Long, String>()

    var onMoneyReceived: ((hp: Double, from: String, note: String) -> Unit)? = null

    // ---------------------------------------------------------------- voice call
    // A phone call over the radio link — no internet, no server: 16 kHz µ-law
    // audio streamed as a Nearby STREAM payload. Works over Bluetooth and gets
    // better when Nearby upgrades the link to Wi-Fi Direct.

    /** state: "ringing" (we called), "incoming" (they called), "active". */
    data class VoiceCall(val endpointId: String, val name: String, val state: String, val startedAt: Long = 0L, val muted: Boolean = false, val speaker: Boolean = true)
    private val _voice = MutableStateFlow<VoiceCall?>(null)
    val voice: StateFlow<VoiceCall?> = _voice
    private var voiceAudio: PcmAudio? = null
    private var voiceOut: PipedOutputStream? = null
    private var voiceQueue: java.util.concurrent.LinkedBlockingQueue<ByteArray>? = null
    private var audioModeSet = false
    private var ringJob: Job? = null
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private fun control(endpointId: String, kind: String) {
        runCatching { client.sendPayload(endpointId, Payload.fromBytes(JSONObject().put("kind", kind).put("from", myName).toString().toByteArray())) }
    }

    /** Ring a connected peer. */
    fun callPeer(p: Peer) {
        if (!p.connected) { _status.value = "Connect to ${p.name} first."; return }
        if (_voice.value != null) return
        control(p.endpointId, "call")
        _voice.value = VoiceCall(p.endpointId, p.name, "ringing")
        ringJob?.cancel()
        ringJob = scope.launch { delay(40_000); if (_voice.value?.state == "ringing") { control(p.endpointId, "call-end"); _voice.value = null; _status.value = "${p.name} didn't answer." } }
    }

    fun acceptCall() {
        val v = _voice.value ?: return
        if (v.state != "incoming") return
        // Only say yes once the microphone is actually running.
        if (startVoice(v.endpointId, v.name)) control(v.endpointId, "call-accept")
    }

    fun declineCall() { val v = _voice.value ?: return; control(v.endpointId, "call-end"); stopVoice("") }

    fun endCall() { val v = _voice.value ?: return; control(v.endpointId, "call-end"); stopVoice("Call ended") }

    fun toggleVoiceMute() { val v = _voice.value ?: return; val m = !v.muted; voiceAudio?.muted = m; _voice.value = v.copy(muted = m) }

    fun toggleVoiceSpeaker() { val v = _voice.value ?: return; val s = !v.speaker; runCatching { audioManager.isSpeakerphoneOn = s }; _voice.value = v.copy(speaker = s) }

    private fun startVoice(endpointId: String, name: String): Boolean {
        ringJob?.cancel(); ringJob = null
        if (voiceAudio != null) return true
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION; audioManager.isSpeakerphoneOn = true; audioModeSet = true }
        // Our microphone -> bounded queue -> writer thread -> pipe -> Nearby stream payload.
        // The queue drops frames when the radio is slower than the mic, so the
        // capture thread never blocks and the call stays live instead of lagging.
        val pin = PipedInputStream(PcmAudio.FRAME_SAMPLES * 8)
        val pout = PipedOutputStream(pin)
        voiceOut = pout
        val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>(8)
        voiceQueue = q
        val silence = ByteArray(PcmAudio.FRAME_SAMPLES) { 0xFF.toByte() }
        val audio = PcmAudio { frame ->
            val f = if (frame.size == PcmAudio.FRAME_SAMPLES) frame else frame.copyOf(PcmAudio.FRAME_SAMPLES).also { silence.copyInto(it, frame.size, frame.size) }
            if (!q.offer(f)) { q.poll(); q.offer(f) }
        }
        if (!audio.start()) { control(endpointId, "call-end"); stopVoice("Microphone isn't available."); return false }
        voiceAudio = audio
        thread(name = "nearby-voice-out", isDaemon = true) {
            try { while (voiceAudio === audio) { val f = q.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue; pout.write(f) } }
            catch (e: Exception) { /* pipe closed: the call ended */ }
        }
        runCatching { client.sendPayload(endpointId, Payload.fromStream(pin)) }
        _voice.value = VoiceCall(endpointId, name, "active", startedAt = System.currentTimeMillis())
        return true
    }

    private fun stopVoice(status: String) {
        ringJob?.cancel(); ringJob = null
        runCatching { voiceAudio?.stop() }; voiceAudio = null
        voiceQueue?.clear(); voiceQueue = null
        runCatching { voiceOut?.close() }; voiceOut = null
        if (audioModeSet) { runCatching { audioManager.mode = AudioManager.MODE_NORMAL; audioManager.isSpeakerphoneOn = false }; audioModeSet = false }
        _voice.value = null
        if (status.isNotBlank()) _status.value = status
    }

    /** The peer's audio arrives as a stream: read 20 ms frames and play them. */
    private fun consumeVoice(payload: Payload) {
        val input = payload.asStream()?.asInputStream() ?: return
        thread(name = "nearby-voice-in", isDaemon = true) {
            val buf = ByteArray(PcmAudio.FRAME_SAMPLES)
            try {
                while (true) {
                    var off = 0
                    while (off < buf.size) { val n = input.read(buf, off, buf.size - off); if (n < 0) return@thread; off += n }
                    voiceAudio?.feed(buf.copyOf())
                }
            } catch (e: Exception) { /* stream closed: the call ended */ }
        }
    }

    /**
     * Devices this user has connected to before, remembered by display name.
     * Endpoint ids are regenerated on every discovery session, so they cannot
     * identify a returning phone — the name is the only stable handle Nearby
     * exposes without exchanging our own identifiers first.
     */
    private val trusted = HashSet<String>()

    /** Called when a remembered peer comes back and is reconnected on its own. */
    var onAutoReconnect: ((name: String) -> Unit)? = null

    fun loadTrusted(names: Set<String>) {
        trusted.clear()
        trusted.addAll(names)
    }

    fun trustedNames(): Set<String> = trusted.toSet()

    // ---------------------------------------------------------------- radio

    /** Nearby silently finds nothing when either of these is off. */
    fun radioState(): RadioState {
        val bt = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.isEnabled == true
        }.getOrDefault(false)

        val loc = runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)

        return RadioState(bluetoothOn = bt, locationServicesOn = loc)
    }

    /**
     * Nearby Connections is a Google Play services API, so on a phone without a
     * working, connected, up-to-date Play services it can never scan — and the
     * only fix is on the user's side. Detect that cleanly so the screen can say
     * *exactly* why instead of asking the user to do something impossible.
     */
    fun gmsState(): GmsState {
        val reason = runCatching {
            val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (code == ConnectionResult.SUCCESS) return GmsState(true, "")
            when (code) {
                ConnectionResult.SERVICE_MISSING ->
                    "Google Play services isn't installed on this phone."
                ConnectionResult.SERVICE_DISABLED ->
                    "Google Play services is switched off."
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
                    "Google Play services needs an update."
                ConnectionResult.SERVICE_INVALID ->
                    "Google Play services isn't working on this phone."
                else -> "Google Play services isn't connected (code $code)."
            }
        }.getOrElse {
            "Google Play services isn't available on this phone."
        }
        return GmsState(false, reason)
    }

    /** Refreshes the [gmsOk] flag the screen listens to. */
    fun refreshGms() {
        _gmsOk.value = gmsState().ok
    }

    /**
     * The right "make it work" intent for the failing Play services state, or
     * null when the OS offers no fix. Falls back to opening the Play services
     * app details page so the user can update/enable it by hand.
     */
    fun gmsFixIntent(): android.content.Intent? {
        // "Update / install Google Play services" is the actionable fix, so open
        // the Play Store on its page. Fall back to the app's own settings page
        // where a manual enable/update is possible.
        val market = runCatching {
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("market://details?id=com.google.android.gms"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()
        return market ?: android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        ).setData(android.net.Uri.parse("package:com.google.android.gms"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---------------------------------------------------------------- lifecycle

    fun start(displayName: String) {
        if (running) return
        myName = displayName.ifBlank { "Axzora user" }
        running = true
        scanRetries = 0
        advertiseRetries = 0

        val radio = radioState()
        if (!radio.ready) {
            _status.value = when {
                !radio.bluetoothOn && !radio.locationServicesOn ->
                    "Turn on Bluetooth and Location to find nearby phones."
                !radio.bluetoothOn -> "Turn on Bluetooth to find nearby phones."
                else -> "Turn on Location (the system toggle, not just the permission)."
            }
            running = false
            return
        }

        // Google Play services is a hard prerequisite for Nearby Connections.
        // If it's not usable, no amount of retrying will start discovery, so
        // say exactly why and hand the fix to the screen's button instead of
        // spinning the radio pointlessly.
        val gms = gmsState()
        _gmsOk.value = gms.ok
        if (!gms.ok) {
            _status.value = gms.reason + " Nearby can't scan without it."
            running = false
            return
        }

        _status.value = "Looking for nearby Axzora phones…"

        advertising = true
        client.startAdvertising(
            myName, SERVICE_ID, connectionCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { e ->
            if (isNotConnected(e) && advertiseRetries < 2) {
                advertiseRetries++
                advertising = false
                scope.launch {
                    delay(1200)
                    if (running && !advertising) {
                        advertising = true
                        client.startAdvertising(
                            myName, SERVICE_ID, connectionCallback,
                            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
                        ).addOnFailureListener { e2 ->
                            advertising = false
                            _status.value = "Can't be discovered: ${reason(e2)}"
                        }
                    }
                }
            } else {
                advertising = false
                _status.value = "Can't be discovered: ${reason(e)}"
            }
        }

        startDiscovery()
    }

    private fun startDiscovery() {
        if (!running || discovering) return
        discovering = true
        client.startDiscovery(
            SERVICE_ID, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { e ->
            // API_NOT_CONNECTED (8003) is the "client hasn't finished connecting
            // yet" race: startDiscovery() fired right as Nearby was still coming
            // up. Wait a beat and retry once before reporting, instead of showing
            // the user a raw code for a link that just needed a moment.
            if (isNotConnected(e) && scanRetries < 2) {
                scanRetries++
                discovering = false
                scope.launch {
                    delay(1200)
                    startDiscovery()
                }
            } else {
                discovering = false
                _status.value = "Can't scan: ${reason(e)}"
            }
        }
    }

    private fun pauseDiscovery() {
        if (!discovering) return
        runCatching { client.stopDiscovery() }
        discovering = false
    }

    /**
     * Frees the radio completely for a handshake. Scanning *and* advertising
     * both keep the Bluetooth controller busy, and leaving either running is
     * why a connection can sit unanswered until it times out.
     */
    private fun pauseRadio() {
        pauseDiscovery()
        if (advertising) {
            runCatching { client.stopAdvertising() }
            advertising = false
        }
    }

    private fun resumeRadio() {
        if (!running) return
        if (!advertising) {
            advertising = true
            client.startAdvertising(
                myName, SERVICE_ID, connectionCallback,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnFailureListener {
                advertising = false
                _status.value = "Can't be discovered: ${reason(it)}"
            }
        }
        startDiscovery()
    }

    fun stop() {
        _voice.value?.let { control(it.endpointId, "call-end"); stopVoice("") }
        running = false
        discovering = false
        advertising = false
        pendingConnects.values.forEach { it.cancel() }
        pendingConnects.clear()
        attempts.clear()
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        _peers.value = emptyList()
        _status.value = ""
    }

    // ---------------------------------------------------------------- connect

    fun connect(peer: Peer) {
        if (pendingConnects.containsKey(peer.endpointId)) return
        if (_peers.value.any { it.endpointId == peer.endpointId && it.connected }) return

        // Free the radio entirely: scanning or advertising during a handshake
        // is what makes connections stall with no callback at all.
        pauseRadio()

        updatePeer(peer.endpointId) { it.copy(connecting = true) }
        _status.value = "Connecting to ${peer.name}…"

        client.requestConnection(myName, peer.endpointId, connectionCallback)
            .addOnFailureListener { e ->
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    finishConnect(peer.endpointId, ok = true, message = null)
                } else {
                    finishConnect(peer.endpointId, ok = false, message = "Couldn't connect: ${reason(e)}")
                }
            }

        // Nearby has no timeout of its own; without this the UI would sit on
        // "Connecting…" indefinitely when a handshake is dropped.
        pendingConnects[peer.endpointId] = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_peers.value.none { it.endpointId == peer.endpointId && it.connected }) {
                runCatching { client.disconnectFromEndpoint(peer.endpointId) }
                finishConnect(
                    peer.endpointId, ok = false,
                    message = "${peer.name} didn't respond. Make sure Nearby is open on their phone, then try again.",
                )
            }
        }
    }

    private fun finishConnect(endpointId: String, ok: Boolean, message: String?) {
        pendingConnects.remove(endpointId)?.cancel()
        updatePeer(endpointId) { it.copy(connecting = false, connected = ok || it.connected) }
        if (message != null) _status.value = message
        else if (ok) _status.value = ""
        // Put the radio back to work so people keep appearing.
        resumeRadio()
    }

    /** Deliberate disconnect also forgets the peer, so it won't come back. */
    fun disconnect(peer: Peer) {
        trusted.remove(peer.name)
        pendingConnects.remove(peer.endpointId)?.cancel()
        runCatching { client.disconnectFromEndpoint(peer.endpointId) }
        updatePeer(peer.endpointId) { it.copy(connected = false, connecting = false) }
    }

    // ---------------------------------------------------------------- sending

    fun sendText(text: String) {
        val body = JSONObject().put("kind", "chat").put("from", myName).put("text", text)
        if (!broadcast(Payload.fromBytes(body.toString().toByteArray()))) return
        add(NearbyMessage(from = myName, text = text, mine = true))
    }

    /** Shares a fix over the radio link — no GPS upload, no server involved. */
    fun sendLocation(lat: Double, lon: Double) {
        val body = JSONObject()
            .put("kind", "location").put("from", myName)
            .put("lat", lat).put("lon", lon)
        if (!broadcast(Payload.fromBytes(body.toString().toByteArray()))) return
        add(NearbyMessage(from = myName, text = "Shared location", mine = true, lat = lat, lon = lon))
    }

    fun sendMoney(hp: Double, note: String) {
        val body = JSONObject()
            .put("kind", "money").put("from", myName).put("hp", hp).put("note", note)
        if (!broadcast(Payload.fromBytes(body.toString().toByteArray()))) return
        add(NearbyMessage(from = myName, text = note, mine = true, hp = hp))
    }

    fun sendFile(uri: Uri, displayName: String) {
        val connected = _peers.value.filter { it.connected }
        if (connected.isEmpty()) { _status.value = "Connect to someone first."; return }

        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd == null) { _status.value = "Couldn't open that file."; return }

        val payload = Payload.fromFile(pfd)
        val header = JSONObject()
            .put("kind", "file").put("from", myName)
            .put("name", displayName).put("payloadId", payload.id)

        connected.forEach { p ->
            client.sendPayload(p.endpointId, Payload.fromBytes(header.toString().toByteArray()))
            client.sendPayload(p.endpointId, payload)
        }
        inFlight[payload.id] = displayName
        add(NearbyMessage(from = myName, text = displayName, mine = true, fileName = displayName, fileUri = uri))
    }

    /** @return false when there was nobody to send to. */
    private fun broadcast(payload: Payload): Boolean {
        val connected = _peers.value.filter { it.connected }
        if (connected.isEmpty()) { _status.value = "Connect to someone first."; return false }
        connected.forEach { client.sendPayload(it.endpointId, payload) }
        return true
    }

    // ---------------------------------------------------------------- callbacks

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val known = _peers.value.firstOrNull { it.endpointId == endpointId }
            if (known == null) {
                _peers.value = _peers.value + Peer(endpointId, info.endpointName)
            }
            if (_status.value.startsWith("Looking")) _status.value = ""

            // Someone already paired with has come back into range: reconnect
            // without making the user tap again.
            if (info.endpointName in trusted &&
                _peers.value.none { it.endpointId == endpointId && (it.connected || it.connecting) }
            ) {
                _status.value = "Reconnecting to ${info.endpointName}…"
                onAutoReconnect?.invoke(info.endpointName)
                connect(Peer(endpointId, info.endpointName))
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // Keep anyone we are mid-handshake with or already talking to.
            _peers.value = _peers.value.filterNot {
                it.endpointId == endpointId && !it.connected && !it.connecting
            }
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Incoming invitation: free the radio so the handshake completes.
            pauseRadio()

            if (_peers.value.none { it.endpointId == endpointId }) {
                _peers.value = _peers.value + Peer(endpointId, info.endpointName, connecting = true)
            } else {
                updatePeer(endpointId) { it.copy(connecting = true) }
            }

            // Family devices on a private radio link: accept automatically so
            // pairing is one tap instead of a code read aloud on both phones.
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    finishConnect(endpointId, ok = false, message = "Couldn't accept: ${reason(it)}")
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val name = _peers.value.firstOrNull { it.endpointId == endpointId }?.name ?: "them"
            when (val code = resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    // Pairing succeeded once, so this phone is trusted to
                    // reconnect silently next time it appears.
                    _peers.value.firstOrNull { it.endpointId == endpointId }
                        ?.let { trusted.add(it.name) }
                    attempts.remove(endpointId)
                    finishConnect(endpointId, ok = true, message = null)
                }
                ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT ->
                    finishConnect(endpointId, ok = true, message = null)

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    finishConnect(endpointId, ok = false, message = "$name declined.")

                ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
                ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN -> {
                    // Almost always transient: a busy radio, or both sides
                    // inviting at the same instant. Worth retrying a few times
                    // before bothering the user, since each attempt is seconds.
                    val n = attempts.getOrDefault(endpointId, 0)
                    if (n < MAX_RETRIES) {
                        attempts[endpointId] = n + 1
                        finishConnect(endpointId, ok = false,
                            message = "Radio was busy — retrying (${n + 1}/$MAX_RETRIES)…")
                        scope.launch {
                            delay(1200L * (n + 1))
                            _peers.value.firstOrNull { it.endpointId == endpointId }?.let { connect(it) }
                        }
                    } else {
                        attempts.remove(endpointId)
                        finishConnect(endpointId, ok = false, message = advice(code, name))
                    }
                }

                else -> {
                    attempts.remove(endpointId)
                    finishConnect(endpointId, ok = false, message = advice(code, name))
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (_voice.value?.endpointId == endpointId) stopVoice((_voice.value?.name ?: "They") + " left the call")
            pendingConnects.remove(endpointId)?.cancel()
            val name = _peers.value.firstOrNull { it.endpointId == endpointId }?.name
            updatePeer(endpointId) { it.copy(connected = false, connecting = false) }
            // Out of range rather than deliberately disconnected: say so, keep
            // scanning, and the discovery callback will reconnect on return.
            if (name != null && name in trusted) {
                _status.value = "$name went out of range — will reconnect automatically."
            }
            resumeRadio()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytes(endpointId, payload)
                Payload.Type.FILE -> inFlight[payload.id] = pendingFileNames[payload.id] ?: "file"
                Payload.Type.STREAM -> consumeVoice(payload)
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val label = inFlight[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val pct = if (update.totalBytes > 0) {
                        ((update.bytesTransferred * 100) / update.totalBytes).toInt()
                    } else 0
                    _transfer.value = TransferProgress(label, pct, incoming = true)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    _transfer.value = null
                    inFlight.remove(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    _transfer.value = null
                    inFlight.remove(update.payloadId)
                    _status.value = "Transfer of $label failed."
                }
            }
        }
    }

    private fun handleBytes(endpointId: String, payload: Payload) {
        val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val from = o.optString("from", "Someone")
        when (o.optString("kind")) {
            // Call signalling is keyed by the endpoint that sent it — never by name.
            "call" -> {
                if (_voice.value == null) _voice.value = VoiceCall(endpointId, from, "incoming")
                else if (_voice.value?.endpointId != endpointId) control(endpointId, "call-end")   // busy
            }
            "call-accept" -> { val v = _voice.value; if (v != null && v.state == "ringing" && v.endpointId == endpointId) startVoice(v.endpointId, v.name) }
            "call-end" -> if (_voice.value?.endpointId == endpointId) stopVoice("Call ended")
            "chat" -> add(NearbyMessage(from = from, text = o.optString("text"), mine = false))
            "money" -> {
                val hp = o.optDouble("hp", 0.0)
                val note = o.optString("note")
                if (hp > 0) {
                    onMoneyReceived?.invoke(hp, from, note)
                    add(NearbyMessage(from = from, text = note, mine = false, hp = hp))
                }
            }
            "location" -> {
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) {
                    add(NearbyMessage(
                        from = from, text = "Shared location",
                        mine = false, lat = lat, lon = lon,
                    ))
                }
            }
            "file" -> {
                pendingFileNames[o.optLong("payloadId")] = o.optString("name", "file")
                add(
                    NearbyMessage(
                        from = from, text = o.optString("name"),
                        mine = false, fileName = o.optString("name"),
                    )
                )
            }
        }
    }

    /**
     * Turns a Nearby status code into something a person can act on. The raw
     * code is kept on the end so a failure can still be reported precisely.
     */
    private fun advice(code: Int, name: String): String {
        val fix = when (code) {
            ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
            ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN ->
                "Couldn't reach $name. Open Nearby on their phone, keep both screens on, and try once more."
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR ->
                "Bluetooth had a problem. Turn Bluetooth off and on again on both phones."
            ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
                "The radio is stuck. Toggle airplane mode on and off, then retry."
            ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING,
            ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING ->
                "Axzora is already running Nearby. Go back and open it again."
            ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL ->
                "Nearby got out of step. Leave this screen and come back."
            else ->
                "Connection failed. Make sure both phones have Nearby open and are within a few metres."
        }
        return "$fix  [${ConnectionsStatusCodes.getStatusCodeString(code)}]"
    }

    private fun reason(e: Exception): String {
        val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        return if (code != null) {
            val human = when (code) {
                com.google.android.gms.common.api.CommonStatusCodes.API_NOT_CONNECTED ->
                    "Google Play services isn't connected. Update Google Play services, then reopen Nearby."
                ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR ->
                    "Bluetooth had a problem — turn it off and on, then retry."
                ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
                    "The radio is stuck — toggle airplane mode, then retry."
                else -> ConnectionsStatusCodes.getStatusCodeString(code)
            }
            "$human  [${ConnectionsStatusCodes.getStatusCodeString(code)}]"
        } else e.message ?: "unknown error"
    }

    private fun isNotConnected(e: Exception): Boolean =
        (e as? com.google.android.gms.common.api.ApiException)?.statusCode ==
            com.google.android.gms.common.api.CommonStatusCodes.API_NOT_CONNECTED

    private fun updatePeer(endpointId: String, edit: (Peer) -> Peer) {
        _peers.value = _peers.value.map { if (it.endpointId == endpointId) edit(it) else it }
    }

    private fun add(m: NearbyMessage) {
        _messages.value = _messages.value + m
    }

    companion object {
        private const val SERVICE_ID = "com.axzora.os.nearby"
        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val MAX_RETRIES = 3
    }
}
