package com.axzora.os.call

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate as RtcIceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/** Where a call currently is. */
enum class CallState { IDLE, DIALLING, RINGING, CONNECTING, ACTIVE, ENDED }

data class CallInfo(
    val state: CallState = CallState.IDLE,
    val callId: String = "",
    val peerUid: String = "",
    val peerName: String = "",
    val incoming: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val video: Boolean = false,
    val remoteVideo: Boolean = false,
    val frontCamera: Boolean = true,
    val error: String = "",
    val startedAt: Long = 0L,
    /** Audio is going through the Axzora server relay (no direct route was found). */
    val relay: Boolean = false,
)

/**
 * Voice calling over WebRTC.
 *
 * Only the call setup goes through the Axzora server; the audio itself is
 * negotiated peer-to-peer where possible. STUN discovers a direct route, and
 * when carrier NAT leaves none — the usual case for two phones on mobile
 * data — the TURN relay handed out by the server carries the media instead.
 */
class CallManager(
    private val context: Context,
    private val signaling: CallSignaling,
    private val myUid: () -> String,
    private val myName: () -> String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _call = MutableStateFlow(CallInfo())
    val call: StateFlow<CallInfo> = _call

    // ---- server relay fallback ----
    private var relay: RelayVoice? = null
    /** Bumped on every dial and every teardown so a stale dial can't resurrect a call. */
    private var dialGen = 0
    private var connectWatch: Job? = null

    /** Give ICE this long after the answer before falling back to the relay. */
    private val CONNECT_GRACE_MS = 12_000L

    private fun armConnectWatch() {
        connectWatch?.cancel()
        connectWatch = scope.launch {
            delay(CONNECT_GRACE_MS)
            if (_call.value.state == CallState.CONNECTING && relay == null) switchToRelay(announce = true)
        }
    }

    /**
     * No direct route (carrier NAT on both sides, no TURN): keep the call alive
     * by sending the audio through the server instead. Both phones end up
     * here — one decides and tells the other through the signalling channel.
     */
    private fun switchToRelay(announce: Boolean) {
        val info = _call.value
        if (relay != null) return
        if (info.state == CallState.ENDED || info.state == CallState.IDLE || info.callId.isBlank()) return
        connectWatch?.cancel(); connectWatch = null
        stopRingback()
        if (announce) scope.launch { signaling.sendIce(info.callId, myUid(), IceCandidate("", 0, "", relay = true)) }
        // The WebRTC leg is done; the relay carries voice only.
        stopLocalVideo()
        _remoteVideo.value = null
        runCatching { peer?.close() }; peer = null
        runCatching { audioSource?.dispose() }; audioSource = null; localTrack = null
        val rv = RelayVoice(
            wsUrl = signaling.voiceUrl(), callId = info.callId, uid = myUid(), scope = scope,
            onDead = { msg -> scope.launch { fail(msg) } },
        )
        rv.muted = info.muted
        relay = rv
        routeAudio(speaker = info.speaker || true)
        _call.value = info.copy(
            state = CallState.ACTIVE, relay = true, video = false, remoteVideo = false, error = "",
            speaker = true,
            startedAt = if (info.startedAt == 0L) System.currentTimeMillis() else info.startedAt,
        )
        scope.launch {
            val ok = runCatching { rv.start() }.getOrDefault(false)
            if (!ok) fail("Couldn't start the relay — microphone busy?")
        }
    }

    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null

    /** Shared with the UI so both renderers use the same GL context. */
    val eglBase: EglBase by lazy { EglBase.create() }

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    private var capturing = false
    private var cameraCount = 0

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Candidates that arrived before the remote description was set.
     *
     * Written from the polling coroutine and drained from WebRTC's own
     * signalling thread, so both the list and the flag guarding it are shared
     * across threads. Unsynchronised, the poller could go on adding to a list
     * that had already been flushed — the candidates would sit there for the
     * rest of the call and the connection would settle for whatever route it
     * had found before, or none.
     */
    private val pendingRemoteIce = mutableListOf<RtcIceCandidate>()

    @Volatile
    private var remoteDescriptionSet = false
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Watches the media counters so a silent call says why it is silent. */
    private val audioWatch = AudioWatch(context, scope)
    val audioHealth: StateFlow<AudioHealth> get() = audioWatch.health

    init {
        // Fetch the relay list now so the first call already has TURN, and
        // keep it fresh in the background rather than blocking a dial.
        scope.launch { refreshIceServers(signaling) }
        watchRooms()
    }

    // ---------------------------------------------------------------- setup

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        // Echo cancellation and noise suppression run in WebRTC's own software
        // (AEC3), never the handset's. A phone may *claim* a built-in
        // canceller, but the implementations vary wildly between manufacturers
        // and broken ones are the single most common cause of echo, muffled
        // sound, and robotic voice. The software path behaves the same on
        // every device, which is what the big call apps ship on too.
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(msg: String?) = micFailed(msg)
                override fun onWebRtcAudioRecordStartError(
                    code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, msg: String?,
                ) = micFailed(msg)
                override fun onWebRtcAudioRecordError(msg: String?) = micFailed(msg)
            })
            .createAudioDeviceModule()

        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    /**
     * True once the microphone has failed to open on this call.
     *
     * Sticky on purpose. The failure is reported while the call is still being
     * set up, and the connection coming up clears [CallInfo.error] — so the one
     * message explaining why the far end hears nothing was being wiped a second
     * after it appeared, leaving a call that looks perfect and carries silence.
     */
    @Volatile
    private var micDead = false
    private var micDetail = ""

    /** A dead microphone used to present as a connected but silent call. */
    private fun micFailed(msg: String?) {
        micDead = true
        micDetail = msg.orEmpty()
        scope.launch {
            val text = micError()
            if (_group.value.active) _group.value = _group.value.copy(error = text)
            else _call.value = _call.value.copy(error = text)
        }
    }

    private fun micError(): String =
        "Your microphone isn't working — they can't hear you." +
            if (micDetail.isBlank()) "" else " ($micDetail)"

    /**
     * Whether the microphone may be opened at all.
     *
     * Without the permission the device module fails at start and the call
     * proceeds as a connected, silent one. Checking up front turns that into a
     * message the user can act on instead of a call the other person gives up
     * on.
     */
    private fun micReady(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val micMissing =
        "Axzora needs microphone permission to make calls. " +
            "Grant it in Settings › Permissions and try again."

    /** Raises the encoder's ceiling; WebRTC still backs off on a poor link. */
    private fun setMaxBitrate(sender: RtpSender?, bps: Int, video: Boolean) {
        val s = sender ?: return
        runCatching {
            val params: RtpParameters = s.parameters ?: return
            params.encodings.forEach { it.maxBitrateBps = bps }
            // Degradation preference describes how to shed video quality, so
            // setting it on an audio sender can make the whole parameter
            // update fail — taking the bitrate change down with it.
            if (video) {
                params.degradationPreference =
                    RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }
            s.parameters = params
        }
    }

    /**
     * Opus starts life capped at a phone-line bitrate. Raising the ceiling
     * and turning on in-band FEC — a low-cost copy of each packet inside the
     * next, so a lost one is reconstructed instead of heard as a dropout —
     * is what makes voice sound like a person rather than a radio.
     *
     * Deliberately mono: the microphone is mono, so asking for stereo just
     * splits the bitrate across a second channel that carries nothing.
     */
    private fun tuneAudio(sdp: String): String {
        val payload = Regex("""a=rtpmap:(\d+) opus/48000""")
            .find(sdp)?.groupValues?.get(1) ?: return sdp

        val wanted = mapOf(
            "maxaveragebitrate" to "$AUDIO_BITRATE",
            "maxplaybackrate" to "48000",
            "useinbandfec" to "1",
            "minptime" to "10",
        )
        val existing = Regex("a=fmtp:$payload ([^\r\n]*)").find(sdp)

        return if (existing != null) {
            // Merge rather than append: a parameter listed twice is undefined
            // behaviour, and decoders disagree about which copy wins.
            val params = existing.groupValues[1].split(";")
                .mapNotNull { p ->
                    val kv = p.trim().split("=", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else null
                }
                .toMap(LinkedHashMap())
            params.putAll(wanted)
            val line = "a=fmtp:$payload " +
                params.entries.joinToString(";") { "${it.key}=${it.value}" }
            sdp.replace(existing.value, line)
        } else {
            // SDP lines are CRLF separated; a bare newline yields a
            // description the far end rejects.
            val line = wanted.entries.joinToString(";") { "${it.key}=${it.value}" }
            Regex("(a=rtpmap:$payload opus/48000[^\r\n]*)").replace(sdp) {
                it.groupValues[1] + "\r\na=fmtp:$payload " + line
            }
        }
    }

    /**
     * Relays fetched from the server, refreshed before each call.
     *
     * STUN alone only works when at least one side sits behind a permissive
     * NAT. Two phones on mobile data usually don't — carrier NAT rewrites
     * ports per destination, so the addresses STUN discovers are useless to
     * the other end. That is the call that shows "connecting" and never
     * connects. A TURN relay carries the media when no direct path exists;
     * it costs bandwidth, so WebRTC only falls back to it when it must.
     */
    @Volatile
    private var relays: List<PeerConnection.IceServer> = emptyList()

    suspend fun refreshIceServers(signaling: CallSignaling) {
        val fetched = signaling.iceServers().map { s ->
            PeerConnection.IceServer.builder(s.url)
                .setUsername(s.username)
                .setPassword(s.credential)
                .createIceServer()
        }
        if (fetched.isNotEmpty()) relays = fetched
    }

    private fun iceServers(): List<PeerConnection.IceServer> =
        relays.ifEmpty {
            // Only reached if the server was unreachable at call time.
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            )
        }

    /**
     * @param onState when given, replaces the one-to-one call's own reaction
     *   to the connection coming up or failing. A group call has several
     *   connections and no single one of them is "the call".
     * @param onRemoteTrack likewise for arriving media: in a group each track
     *   belongs to a named person rather than to "the other side".
     */
    private fun createPeer(
        onLocalIce: (RtcIceCandidate) -> Unit,
        onState: ((PeerConnection.IceConnectionState) -> Unit)? = null,
        onRemoteTrack: ((org.webrtc.MediaStreamTrack) -> Unit)? = null,
    ): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return ensureFactory().createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(c: RtcIceCandidate) = onLocalIce(c)
                override fun onIceCandidatesRemoved(candidates: Array<out RtcIceCandidate>?) {}
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    if (onState != null) {
                        scope.launch { s?.let(onState) }
                        return
                    }
                    scope.launch {
                        when (s) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                connectWatch?.cancel(); connectWatch = null
                                if (relay != null) return@launch   // already on the relay; ignore the late leg
                                // Connected: the far end picked up, so the
                                // ringing tone has done its job.
                                stopRingback()
                                _call.value = _call.value.copy(
                                    state = CallState.ACTIVE,
                                    startedAt = if (_call.value.startedAt == 0L)
                                        System.currentTimeMillis() else _call.value.startedAt,
                                    // Clearing the error is how a transient
                                    // "reconnecting…" gets tidied away, but a
                                    // dead microphone is not transient and the
                                    // message has to survive it.
                                    error = if (micDead) micError() else "",
                                )
                                // Only now is there media to measure.
                                audioWatch.start(peer = { peer }, muted = { _call.value.muted })
                            }
                            PeerConnection.IceConnectionState.FAILED ->
                                // No direct path: carry the voice through the server instead.
                                switchToRelay(announce = true)
                            PeerConnection.IceConnectionState.DISCONNECTED ->
                                if (relay == null) _call.value = _call.value.copy(error = "Connection lost — reconnecting…")
                            else -> {}
                        }
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    // Same reasoning as the outgoing track: a received track
                    // that arrives disabled plays silence, and the call looks
                    // healthy while one side hears nothing.
                    if (track is AudioTrack) runCatching { track.setEnabled(true) }
                    if (onRemoteTrack != null) {
                        track?.let { t -> scope.launch { onRemoteTrack(t) } }
                        return
                    }
                    if (track is VideoTrack) {
                        scope.launch { _remoteVideo.value = track }
                        // A video channel now exists, but the far side may still
                        // have its camera off. Only claim they are on video once
                        // a frame actually arrives, so the layout doesn't switch
                        // to a black rectangle.
                        runCatching {
                            track.addSink { _ ->
                                if (!_call.value.remoteVideo) {
                                    scope.launch {
                                        _call.value = _call.value.copy(remoteVideo = true)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    /**
     * Opens a camera and publishes it. Both sides always negotiate a video
     * transceiver so video can be switched on mid-call without renegotiating
     * the whole session — the track simply starts producing frames.
     */
    /**
     * Creates the camera track and attaches it to the connection — always,
     * even for an audio call.
     *
     * WebRTC fixes the media sections when the session is negotiated. Adding
     * video only for video calls meant an audio call had no video channel at
     * all, so the far side's camera could never be received and switching video
     * on mid-call sent frames nowhere. Publishing a (possibly disabled) track
     * up front means video can be turned on later with no renegotiation.
     *
     * The camera itself is only opened once video is actually enabled, so an
     * audio call never lights the camera indicator.
     */
    private fun addLocalVideo(pc: PeerConnection, front: Boolean, enabled: Boolean) {
        val track = ensureLocalVideo(front, enabled) ?: return
        val sender = pc.addTrack(track, listOf("axzora_stream"))
        // Without an explicit ceiling WebRTC settles on a conservative
        // bitrate and the picture stays soft even on a fast connection.
        setMaxBitrate(sender, VIDEO_BITRATE, video = true)
    }

    /** One camera, however many people are being sent to. */
    private fun ensureLocalVideo(front: Boolean, enabled: Boolean): VideoTrack? {
        localVideoTrack?.let { return it }
        val f = ensureFactory()
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        if (names.isEmpty()) {
            scope.launch { _call.value = _call.value.copy(error = "No camera on this device.") }
            return null
        }
        cameraCount = names.size

        val target = names.firstOrNull {
            if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: names.first()

        val capturer = enumerator.createCapturer(target, null) ?: return null
        val helper = SurfaceTextureHelper.create("AxzoraCapture", eglBase.eglBaseContext)
        val source = f.createVideoSource(false)
        capturer.initialize(helper, context, source.capturerObserver)

        val track = f.createVideoTrack("axzora_video", source)
        track.setEnabled(enabled)

        videoCapturer = capturer
        videoSource = source
        surfaceHelper = helper
        localVideoTrack = track

        if (enabled) startCapture()
        return track
    }

    private fun startCapture() {
        if (capturing) return
        runCatching { videoCapturer?.startCapture(1280, 720, 30) }
            .onSuccess {
                capturing = true
                _localVideo.value = localVideoTrack
            }
            .onFailure { e ->
                scope.launch { _call.value = _call.value.copy(error = "Camera failed: ${e.message}") }
            }
    }

    private fun stopCapture() {
        if (!capturing) return
        runCatching { (videoCapturer as? CameraVideoCapturer)?.stopCapture() }
        capturing = false
        _localVideo.value = null
    }

    private fun stopLocalVideo() {
        stopCapture()
        runCatching { videoCapturer?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        videoCapturer = null
        videoSource = null
        surfaceHelper = null
        localVideoTrack = null
        _localVideo.value = null
    }

    /** Turns the camera on or off during a call. No renegotiation needed. */
    fun toggleVideo() {
        if (relay != null) {
            _call.value = _call.value.copy(error = "Video needs a direct connection — this call is going through the relay.")
            return
        }
        val track = localVideoTrack ?: run {
            _call.value = _call.value.copy(error = "Camera isn't available on this call.")
            return
        }
        val next = !_call.value.video
        track.setEnabled(next)
        if (next) startCapture() else stopCapture()
        _call.value = _call.value.copy(video = next, speaker = next || _call.value.speaker)
        if (next) routeAudio(true)
    }

    fun switchCamera() {
        if (cameraCount < 2) {
            _call.value = _call.value.copy(error = "This device has only one camera.")
            return
        }
        if (!capturing) {
            _call.value = _call.value.copy(error = "Turn video on before switching camera.")
            return
        }
        val cap = videoCapturer as? CameraVideoCapturer ?: run {
            _call.value = _call.value.copy(error = "This camera can't be switched.")
            return
        }
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                scope.launch {
                    _call.value = _call.value.copy(frontCamera = isFront, error = "")
                    _group.value = _group.value.copy(frontCamera = isFront, error = "")
                }
            }
            override fun onCameraSwitchError(error: String?) {
                // Surface the real reason rather than a generic failure.
                scope.launch {
                    _call.value = _call.value.copy(error = "Camera switch failed: ${error ?: "unknown"}")
                }
            }
        })
    }

    /**
     * The microphone, made once.
     *
     * A group call is several connections carrying the same voice. Creating a
     * source per connection would open the microphone several times over --
     * on most phones the second attempt simply fails, and everyone after the
     * first person hears silence.
     */
    private fun ensureLocalAudio(): AudioTrack {
        localTrack?.let { return it }
        val f = ensureFactory()
        // Ask for the whole processing chain by name. These map onto WebRTC's
        // AudioOptions: echo cancellation, gain control so quiet talkers are
        // brought up, noise suppression for fans and traffic, and a high-pass
        // filter that strips the low-frequency rumble phones pick up from
        // being held.
        val processing = MediaConstraints().apply {
            listOf(
                "googEchoCancellation", "googAutoGainControl",
                "googNoiseSuppression", "googHighpassFilter",
            ).forEach { mandatory.add(MediaConstraints.KeyValuePair(it, "true")) }
        }
        val src = f.createAudioSource(processing)
        val track = f.createAudioTrack("axzora_audio", src)
        // Never rely on the default: a track that starts disabled transmits
        // silence, and the other side hears nothing while everything else
        // looks connected.
        track.setEnabled(true)
        audioSource = src
        localTrack = track
        return track
    }

    private fun addLocalAudio(pc: PeerConnection) {
        val sender = pc.addTrack(ensureLocalAudio(), listOf("axzora_stream"))
        setMaxBitrate(sender, AUDIO_BITRATE, video = false)
    }

    // ---------------------------------------------------------------- calling

    /** True while this phone is on any call, one-to-one or group. */
    private fun busy(): Boolean = _group.value.active ||
        (_call.value.state != CallState.IDLE && _call.value.state != CallState.ENDED)

    fun startCall(peerUid: String, peerName: String, video: Boolean = false) {
        if (busy()) return
        if (!micReady()) {
            _call.value = CallInfo(state = CallState.ENDED, peerName = peerName, error = micMissing)
            return
        }
        micDead = false
        _call.value = CallInfo(
            state = CallState.DIALLING, peerUid = peerUid, peerName = peerName,
            incoming = false, video = video, speaker = true,
        )
        // Loudspeaker by default. Routing a voice call to the earpiece meant
        // anyone not holding the phone to their ear heard nothing and read it
        // as a broken call; the in-call button switches back.
        routeAudio(speaker = true)
        CallBus.callActive = true

        val gen = ++dialGen
        scope.launch {
        // A relay the admin set a minute ago must count for this call.
        withTimeoutOrNull(2_500) { refreshIceServers(signaling) }
        if (gen != dialGen || _call.value.state != CallState.DIALLING) return@launch   // hung up / redialled while we looked

        val pc = createPeer(onLocalIce = { ice -> queueLocalIce(ice) }) ?: run {
            fail("Couldn't start the call engine."); return@launch
        }
        peer = pc
        addLocalAudio(pc)
        addLocalVideo(pc, front = true, enabled = video)

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Both sides must agree the wider Opus settings, so the tuned
                // description is what gets published as well as applied.
                val tuned = SessionDescription(desc.type, tuneAudio(desc.description))
                pc.setLocalDescription(SimpleSdpObserver(), tuned)
                scope.launch {
                    val id = signaling.invite(myUid(), myName(), peerUid, tuned.description)
                    if (gen != dialGen || _call.value.state != CallState.DIALLING) {
                        // Hung up while the invite was in flight: don't let the far end ring.
                        if (id != null) signaling.hangup(id)
                        runCatching { pc.close() }
                        return@launch
                    }
                    if (id == null) { fail("Couldn't reach $peerName."); return@launch }
                    _call.value = _call.value.copy(callId = id, state = CallState.RINGING)
                    // The far end is ringing now — let the caller hear that.
                    startRingback()
                    startPolling()
                }
            }
            override fun onCreateFailure(error: String?) {
                scope.launch { fail("Couldn't create the call: $error") }
            }
        }, MediaConstraints())
        }
    }

    /**
     * Puts the UI into the ringing state for a call that has arrived, without
     * answering it yet — the user still has to accept.
     */
    fun showIncoming(incoming: IncomingCall) {
        if (busy()) return
        _call.value = CallInfo(
            state = CallState.RINGING,
            callId = incoming.callId,
            peerUid = incoming.from,
            peerName = incoming.fromName,
            incoming = true,
        )
        // The notification is suppressed while the app is open, so without
        // this an arriving call made no sound at all.
        ringer.start()

        // Nothing polls while a call is merely ringing — the call engine's own
        // loop only starts once it is answered — so a caller who gives up is
        // noticed by the background service, and only if that service is still
        // alive. This is the backstop for when it isn't: the same timeout the
        // server and the caller use, after which a call that nobody answered
        // stops being one. Without it an aggressive OEM killing the service
        // left the phone ringing and buzzing until it was force-stopped.
        ringWatch?.cancel()
        ringWatch = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (_call.value.state == CallState.RINGING &&
                _call.value.callId == incoming.callId
            ) {
                teardown(CallState.ENDED, "Missed call")
            }
        }
    }

    /** Gives up on an unanswered incoming call. See [showIncoming]. */
    private var ringWatch: kotlinx.coroutines.Job? = null

    fun acceptCall(incoming: IncomingCall, video: Boolean = false) {
        ringer.stop()
        ringWatch?.cancel(); ringWatch = null
        if (!micReady()) {
            // Hang up rather than answer into silence: the caller is better
            // served by the call failing than by one that connects and
            // carries nothing.
            scope.launch { signaling.hangup(incoming.callId) }
            _call.value = CallInfo(
                state = CallState.ENDED, peerName = incoming.fromName, error = micMissing,
            )
            return
        }
        micDead = false
        _call.value = CallInfo(
            state = CallState.CONNECTING, callId = incoming.callId,
            peerUid = incoming.from, peerName = incoming.fromName, incoming = true,
            video = video, speaker = true,
        )
        routeAudio(speaker = true)
        CallBus.callActive = true
        armConnectWatch()

        val pc = createPeer(onLocalIce = { ice -> queueLocalIce(ice) }) ?: run {
            fail("Couldn't start the call engine."); return
        }
        peer = pc
        addLocalAudio(pc)
        addLocalVideo(pc, front = true, enabled = video)

        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    flushPendingIce()
                    pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            val tuned = SessionDescription(desc.type, tuneAudio(desc.description))
                            pc.setLocalDescription(SimpleSdpObserver(), tuned)
                            scope.launch {
                                if (_call.value.callId != incoming.callId || _call.value.state == CallState.ENDED) return@launch
                                signaling.answer(incoming.callId, tuned.description)
                                startPolling()
                            }
                        }
                    }, MediaConstraints())
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, incoming.sdp),
        )
    }

    fun hangUp() {
        val id = _call.value.callId
        if (id.isNotBlank()) scope.launch { signaling.hangup(id) }
        teardown(CallState.ENDED, "")
    }

    /**
     * Drops a finished call back to idle once its reason has been read.
     *
     * A call that fails writes why into [CallInfo.error] — the mic is missing,
     * the other phone was unreachable, no route existed. None of it was ever
     * seen: the call screen only draws while a call is live, so the state
     * carrying the explanation was the one state nothing rendered, and every
     * failure looked identical from the outside.
     */
    fun clearEnded() {
        if (_call.value.state == CallState.ENDED) _call.value = CallInfo()
        if (!_group.value.active && _group.value.error.isNotBlank()) _group.value = GroupInfo()
    }

    fun toggleMute() {
        val next = !_call.value.muted
        relay?.muted = next
        localTrack?.setEnabled(!next)
        _call.value = _call.value.copy(muted = next)
    }

    fun toggleSpeaker() {
        val next = !_call.value.speaker
        routeAudio(next)
        _call.value = _call.value.copy(speaker = next)
    }

    @Suppress("DEPRECATION")
    private var focusRequest: android.media.AudioFocusRequest? = null

    /**
     * The purring tone you hear while the other phone rings.
     *
     * Without it a placed call is silent until answered, which is
     * indistinguishable from a call that failed — people hang up and try
     * again. It plays on the call stream so it follows the earpiece/speaker
     * routing, and stops the moment the call connects or ends.
     */
    private var ringback: android.media.ToneGenerator? = null

    /** Rings for an arriving call while Axzora itself is on screen. */
    private val ringer = Ringer(context)

    private fun startRingback() {
        if (ringback != null) return
        runCatching {
            ringback = android.media.ToneGenerator(
                AudioManager.STREAM_VOICE_CALL, 70,
            ).also { it.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE) }
        }
    }

    /**
     * Blanks the screen while the phone is held to the ear.
     *
     * Without it a cheek resting on the display presses whatever is under it
     * — usually the red End button, since it is the largest target — and the
     * call drops for no visible reason. Every phone app does this; it is the
     * difference between a call you can hold normally and one you have to
     * keep away from your face.
     *
     * Only for ear-held calls: on speaker or video the screen must stay on.
     */
    private var proximityLock: android.os.PowerManager.WakeLock? = null

    private fun holdProximity(hold: Boolean) {
        if (!hold) {
            runCatching { if (proximityLock?.isHeld == true) proximityLock?.release() }
            proximityLock = null
            return
        }
        if (proximityLock?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val field = android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
            if (!pm.isWakeLockLevelSupported(field)) return
            proximityLock = pm.newWakeLock(field, "axzora:call").also {
                it.setReferenceCounted(false)
                it.acquire(60 * 60 * 1000L)
            }
        }
    }

    private fun stopRingback() {
        runCatching { ringback?.stopTone() }
        runCatching { ringback?.release() }
        ringback = null
    }

    private fun routeAudio(speaker: Boolean) {
        // Ear-held only: speaker and video calls are looked at, not listened
        // to with the phone against your face.
        holdProximity(!speaker && !_call.value.video && !_group.value.video)
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speaker

            // In-call audio has its own volume, separate from media and
            // ringtone. If it happens to sit at zero the call is silent with
            // no sign why, and the volume keys adjust the wrong stream — so
            // lift it off the floor to something audible.
            val stream = AudioManager.STREAM_VOICE_CALL
            val max = audioManager.getStreamMaxVolume(stream)
            if (audioManager.getStreamVolume(stream) < max / 3) {
                runCatching { audioManager.setStreamVolume(stream, max / 2, 0) }
            }
            // Without audio focus, whatever was playing — music, a video —
            // keeps playing over the call and drowns the other voice. Asking
            // for transient focus pauses it for the call and hands it back
            // on hangup.
            if (focusRequest == null) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = android.media.AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build()
                audioManager.requestAudioFocus(req)
                focusRequest = req
            }
        }
    }

    // ---------------------------------------------------------------- signalling loop

    private fun queueLocalIce(ice: RtcIceCandidate) {
        val id = _call.value.callId
        if (id.isBlank()) return
        scope.launch {
            signaling.sendIce(
                id, myUid(),
                IceCandidate(ice.sdpMid ?: "", ice.sdpMLineIndex, ice.sdp ?: ""),
            )
        }
    }

    /**
     * Marks the remote description as landed and hands over everything held
     * for it. Raising the flag and draining the list are one step, so a
     * candidate arriving at that moment cannot fall between them.
     */
    private fun flushPendingIce() {
        val queued = synchronized(pendingRemoteIce) {
            remoteDescriptionSet = true
            pendingRemoteIce.toList().also { pendingRemoteIce.clear() }
        }
        queued.forEach { peer?.addIceCandidate(it) }
    }

    /**
     * Hands a candidate to the connection, or holds it if the remote
     * description has not landed yet. The check and the append have to happen
     * together: between a bare read of the flag and the append, the flush can
     * run and the candidate is stranded.
     */
    private fun addRemoteIce(c: IceCandidate) {
        if (c.relay) { switchToRelay(announce = false); return }
        if (relay != null) return
        val rtc = RtcIceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
        val deferred = synchronized(pendingRemoteIce) {
            if (remoteDescriptionSet) false else { pendingRemoteIce += rtc; true }
        }
        if (!deferred) peer?.addIceCandidate(rtc)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (_call.value.state != CallState.IDLE && _call.value.state != CallState.ENDED) {
                // Held open by the server: the answer and each ICE candidate
                // arrive the moment the other phone produces them, which is
                // most of the difference between connecting in one second
                // and connecting in ten.
                val r = signaling.poll(myUid(), waitMs = 15_000)
                val id = _call.value.callId

                // Candidates another poller drained while this call was still
                // ringing — collected here so nothing is lost to the race.
                CallBus.collectIce(id).forEach { addRemoteIce(it) }

                r.answers[id]?.let { sdp ->
                    if (!remoteDescriptionSet && relay == null) {
                        _call.value = _call.value.copy(state = CallState.CONNECTING)
                        armConnectWatch()
                        peer?.setRemoteDescription(
                            object : SimpleSdpObserver() {
                                override fun onSetSuccess() = flushPendingIce()
                            },
                            SessionDescription(SessionDescription.Type.ANSWER, sdp),
                        )
                    }
                }

                r.ice[id]?.forEach { addRemoteIce(it) }

                if (id in r.ended) { teardown(CallState.ENDED, "Call ended"); break }
                // Only a guard against spinning when the server answers at once.
                delay(150)
            }
        }
    }

    /**
     * Watches for calls arriving while no call is in progress.
     *
     * A group call runs its own, faster loop; this one steps aside then,
     * because the server hands each candidate to whichever request drains it
     * first and two loops would take turns swallowing each other's.
     */
    /**
     * Watches for group invitations whenever no call is in progress.
     *
     * This is what puts an invitation on screen. It used to live inside
     * [listen], which nothing ever called — so the state the UI reads was
     * never written and a group invite could not appear at all, however
     * correctly the server delivered it. Started from init so it is running
     * before anyone can be invited.
     */
    private fun watchRooms() {
        scope.launch {
            while (true) {
                val idle = _call.value.state == CallState.IDLE ||
                    _call.value.state == CallState.ENDED
                val uid = myUid()
                if (idle && !_group.value.active && uid.isNotBlank()) {
                    val r = signaling.poll(uid)
                    // Bank drained candidates rather than dropping them, or a
                    // ringing call loses the routes it needs.
                    r.ice.forEach { (callId, list) -> CallBus.bankIce(callId, list) }

                    val invite = r.roomInvites.firstOrNull()
                    if (invite?.roomId != _roomInvite.value?.roomId) {
                        _roomInvite.value = invite
                        // An invitation is a ringing phone. Without this it
                        // appeared in silence, which reads as nothing having
                        // happened at all.
                        if (invite != null) ringer.start() else ringer.stop()
                    }
                }
                delay(2000)
            }
        }
    }

    fun listen(onIncoming: (IncomingCall) -> Unit) {
        scope.launch {
            while (true) {
                if (!_group.value.active &&
                    (_call.value.state == CallState.IDLE || _call.value.state == CallState.ENDED)
                ) {
                    val uid = myUid()
                    if (uid.isNotBlank()) {
                        val r = signaling.poll(uid)
                        // This poll drains too — bank rather than drop, or a
                        // ringing call loses the routes it needs to connect.
                        r.ice.forEach { (callId, list) -> CallBus.bankIce(callId, list) }

                        // A group invitation is not a ringing call: nobody has
                        // dialled us yet. It is an offer to join, and it stops
                        // being offered the moment the room closes.
                        val invite = r.roomInvites.firstOrNull()
                        if (invite?.roomId != _roomInvite.value?.roomId) {
                            _roomInvite.value = invite
                        }

                        // Legs of a group are answered silently; a leg for a
                        // room we are not in would be a stray and is ignored.
                        r.incoming.lastOrNull { it.roomId.isBlank() }?.let(onIncoming)
                    }
                }
                delay(3000)
            }
        }
    }

    // ------------------------------------------------------------ group calls

    /** One connection, to one other person. */
    private class Leg(val uid: String, val name: String) {
        var callId: String = ""
        var pc: PeerConnection? = null
        var remoteSet = false
        /** Candidates that arrived before this leg had a remote description. */
        val pendingIce = mutableListOf<RtcIceCandidate>()
        /** Our own candidates, found before the invite came back with an id. */
        val outgoingIce = mutableListOf<RtcIceCandidate>()
    }

    private val legs = LinkedHashMap<String, Leg>()
    private var groupPollJob: kotlinx.coroutines.Job? = null

    private val _group = MutableStateFlow(GroupInfo())
    val group: StateFlow<GroupInfo> = _group

    /** A group call this phone has been asked into, waiting for an answer. */
    private val _roomInvite = MutableStateFlow<Room?>(null)
    val roomInvite: StateFlow<Room?> = _roomInvite

    /**
     * Opens a group call and rings everyone on the list.
     *
     * Nobody is dialled from here. The room is the invitation; each person
     * who accepts dials everyone already inside, this phone included. That
     * one rule is what stops two phones ringing each other at once as a
     * group fills up.
     */
    fun startGroupCall(
        members: List<RoomMember>,
        video: Boolean = false,
        title: String = "Group call",
    ) {
        if (busy() || members.isEmpty()) return
        if (!micReady()) { _group.value = GroupInfo(error = micMissing); return }
        micDead = false
        _group.value = GroupInfo(
            active = true, title = title, video = video, speaker = true,
            people = members.map { GroupPerson(it.uid, it.name) },
        )
        routeAudio(speaker = true)
        CallBus.callActive = true
        // Open the microphone now rather than when the first person answers:
        // a leg created against a half-built media stack sends silence.
        ensureLocalAudio()
        ensureLocalVideo(front = true, enabled = video)
        startRingback()

        scope.launch {
            val room = signaling.createRoom(myUid(), myName(), members.map { it.uid }, title)
            if (room == null) {
                teardownGroup(signaling.lastError.ifBlank { "Couldn't open the group call." })
                return@launch
            }
            _group.value = _group.value.copy(roomId = room.roomId)
            startGroupPolling()

            // Nobody in: give up rather than ring into the void forever.
            // Checked against this room in particular, or a call started
            // within the minute after giving up would inherit the timeout
            // and hang itself up.
            delay(RING_TIMEOUT_MS)
            if (_group.value.active &&
                _group.value.roomId == room.roomId &&
                _group.value.connecting
            ) {
                leaveGroup(reason = "No answer.")
            }
        }
    }

    /**
     * Starts a PUBLIC live broadcast. Like a group call, but with no invite
     * list and nobody rung: the room is open, shows in /live/list, and a
     * viewer joining is connected to the camera automatically.
     */
    fun startLiveBroadcast(title: String) {
        if (busy()) return
        if (!micReady()) { _group.value = GroupInfo(error = micMissing); return }
        micDead = false
        _group.value = GroupInfo(
            active = true, title = title, video = true, speaker = true,
            people = listOf(GroupPerson(myUid(), myName())),
        )
        routeAudio(speaker = true)
        CallBus.callActive = true
        ensureLocalAudio()
        ensureLocalVideo(front = true, enabled = true)
        scope.launch {
            val room = signaling.startLive(myUid(), myName(), title)
            if (room == null) {
                teardownGroup(signaling.lastError.ifBlank { "Couldn't start the live." })
                return@launch
            }
            _group.value = _group.value.copy(roomId = room.roomId)
            startGroupPolling()
        }
    }

    /**
     * Adds someone to the group call already in progress.
     *
     * They are rung where they are; when they accept, they dial everyone
     * currently inside, so nobody already talking has to do anything. The
     * call carries on uninterrupted throughout.
     */
    fun addToGroupCall(uids: List<String>) {
        val roomId = _group.value.roomId
        if (!_group.value.active || roomId.isBlank() || uids.isEmpty()) return
        scope.launch {
            val room = signaling.inviteToRoom(roomId, myUid(), uids)
            if (room == null) {
                _group.value = _group.value.copy(
                    error = signaling.lastError.ifBlank { "Couldn't add them." },
                )
            }
        }
    }

    /** Accepts an invitation: joins, then dials everyone already inside. */
    fun joinGroupCall(room: Room, video: Boolean = false) {
        ringer.stop()
        if (busy()) return
        if (!micReady()) {
            _roomInvite.value = null
            _group.value = GroupInfo(error = micMissing)
            return
        }
        micDead = false
        _roomInvite.value = null
        _group.value = GroupInfo(
            active = true, roomId = room.roomId, title = room.title,
            video = video, speaker = true,
            people = room.members.filter { it.uid != myUid() }
                .map { GroupPerson(it.uid, it.name) },
        )
        routeAudio(speaker = true)
        CallBus.callActive = true
        ensureLocalAudio()
        ensureLocalVideo(front = true, enabled = video)

        scope.launch {
            val joined = signaling.joinRoom(room.roomId, myUid(), myName())
            if (joined == null) {
                teardownGroup(signaling.lastError.ifBlank { "Couldn't join the group call." })
                return@launch
            }
            _group.value = _group.value.copy(title = joined.title)
            startGroupPolling()
            joined.peers.forEach { dialLeg(it) }
        }
    }

    fun declineGroupInvite(room: Room) {
        ringer.stop()
        _roomInvite.value = null
        scope.launch { signaling.leaveRoom(room.roomId, myUid()) }
    }

    fun leaveGroup(reason: String = "") {
        val room = _group.value.roomId
        val ids = legs.values.mapNotNull { it.callId.ifBlank { null } }
        scope.launch {
            ids.forEach { signaling.hangup(it) }
            if (room.isNotBlank()) signaling.leaveRoom(room, myUid())
        }
        teardownGroup(reason)
    }

    fun toggleGroupMute() {
        val next = !_group.value.muted
        localTrack?.setEnabled(!next)
        _group.value = _group.value.copy(muted = next)
    }

    fun toggleGroupSpeaker() {
        val next = !_group.value.speaker
        _group.value = _group.value.copy(speaker = next)
        routeAudio(next)
    }

    fun toggleGroupVideo() {
        val track = localVideoTrack ?: run {
            _group.value = _group.value.copy(error = "Camera isn't available on this call.")
            return
        }
        val next = !_group.value.video
        track.setEnabled(next)
        if (next) startCapture() else stopCapture()
        _group.value = _group.value.copy(video = next, speaker = next || _group.value.speaker)
        if (next) routeAudio(true)
    }

    // ---- one leg of the mesh ----

    private fun dialLeg(member: RoomMember) {
        if (member.uid == myUid() || legs.containsKey(member.uid)) return
        val leg = Leg(member.uid, member.name)
        legs[member.uid] = leg

        val pc = createPeer(
            onLocalIce = { ice -> queueLegIce(leg, ice) },
            onState = { st -> onLegState(leg, st) },
            onRemoteTrack = { t -> onLegTrack(leg, t) },
        ) ?: run { legs.remove(member.uid); return }
        leg.pc = pc
        addLocalAudio(pc)
        addLocalVideo(pc, front = true, enabled = _group.value.video)
        rememberPerson(member.uid, member.name)

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val tuned = SessionDescription(desc.type, tuneAudio(desc.description))
                pc.setLocalDescription(SimpleSdpObserver(), tuned)
                scope.launch {
                    val id = signaling.invite(
                        myUid(), myName(), member.uid, tuned.description,
                        roomId = _group.value.roomId,
                    )
                    if (id == null) { dropLeg(member.uid); return@launch }
                    leg.callId = id
                    flushLegLocalIce(leg)
                }
            }
            override fun onCreateFailure(error: String?) {
                scope.launch { dropLeg(member.uid) }
            }
        }, MediaConstraints())
    }

    /**
     * Answers a leg without ringing.
     *
     * This is someone joining a group already in progress. The user agreed to
     * the call once; being asked again each time another person arrives would
     * make a four-way call three interruptions long.
     */
    private fun answerLeg(inc: IncomingCall) {
        if (inc.from == myUid() || legs.containsKey(inc.from)) return
        val leg = Leg(inc.from, inc.fromName).also { it.callId = inc.callId }
        legs[inc.from] = leg

        val pc = createPeer(
            onLocalIce = { ice -> queueLegIce(leg, ice) },
            onState = { st -> onLegState(leg, st) },
            onRemoteTrack = { t -> onLegTrack(leg, t) },
        ) ?: run { legs.remove(inc.from); return }
        leg.pc = pc
        addLocalAudio(pc)
        addLocalVideo(pc, front = true, enabled = _group.value.video)
        rememberPerson(inc.from, inc.fromName)

        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    scope.launch {
                        leg.remoteSet = true
                        flushLegRemoteIce(leg)
                    }
                    pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            val tuned = SessionDescription(desc.type, tuneAudio(desc.description))
                            pc.setLocalDescription(SimpleSdpObserver(), tuned)
                            scope.launch {
                                signaling.answer(inc.callId, tuned.description)
                                flushLegLocalIce(leg)
                            }
                        }
                    }, MediaConstraints())
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, inc.sdp),
        )
    }

    private fun queueLegIce(leg: Leg, ice: RtcIceCandidate) {
        // Candidates start arriving the moment the connection is built, which
        // is before the server has given this leg an id. Holding them costs
        // nothing; dropping them loses the earliest and usually best routes.
        if (leg.callId.isBlank()) {
            synchronized(leg.outgoingIce) { leg.outgoingIce += ice }
            return
        }
        scope.launch {
            signaling.sendIce(
                leg.callId, myUid(),
                IceCandidate(ice.sdpMid ?: "", ice.sdpMLineIndex, ice.sdp ?: ""),
            )
        }
    }

    private fun flushLegLocalIce(leg: Leg) {
        val queued = synchronized(leg.outgoingIce) {
            leg.outgoingIce.toList().also { leg.outgoingIce.clear() }
        }
        queued.forEach { queueLegIce(leg, it) }
    }

    private fun addLegIce(leg: Leg, c: IceCandidate) {
        val rtc = RtcIceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
        if (leg.remoteSet) leg.pc?.addIceCandidate(rtc) else leg.pendingIce += rtc
    }

    private fun flushLegRemoteIce(leg: Leg) {
        leg.pendingIce.forEach { leg.pc?.addIceCandidate(it) }
        leg.pendingIce.clear()
    }

    private fun onLegState(leg: Leg, st: PeerConnection.IceConnectionState) {
        when (st) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                stopRingback()
                updatePerson(leg.uid) { it.copy(connected = true) }
                if (_group.value.startedAt == 0L) {
                    _group.value = _group.value.copy(
                        startedAt = System.currentTimeMillis(),
                        error = if (micDead) micError() else "",
                    )
                    // The microphone is shared across every leg, so watching
                    // one of them answers "can they hear me" for the whole
                    // room. Started on the first leg to come up only.
                    audioWatch.start(
                        peer = { legs.values.firstOrNull { l -> l.pc != null }?.pc },
                        muted = { _group.value.muted },
                    )
                }
            }
            PeerConnection.IceConnectionState.FAILED -> dropLeg(leg.uid)
            PeerConnection.IceConnectionState.DISCONNECTED ->
                updatePerson(leg.uid) { it.copy(connected = false) }
            else -> {}
        }
    }

    private fun onLegTrack(leg: Leg, track: org.webrtc.MediaStreamTrack) {
        if (track !is VideoTrack) return
        updatePerson(leg.uid) { it.copy(video = track) }
        runCatching {
            track.addSink { _ ->
                val known = _group.value.people.firstOrNull { it.uid == leg.uid }
                if (known?.hasVideo != true) {
                    scope.launch { updatePerson(leg.uid) { p -> p.copy(hasVideo = true) } }
                }
            }
        }
    }

    private fun rememberPerson(uid: String, name: String) {
        val list = _group.value.people.toMutableList()
        val i = list.indexOfFirst { it.uid == uid }
        if (i >= 0) list[i] = list[i].copy(name = name.ifBlank { list[i].name })
        else list += GroupPerson(uid, name)
        _group.value = _group.value.copy(people = list)
    }

    private fun updatePerson(uid: String, block: (GroupPerson) -> GroupPerson) {
        val list = _group.value.people.toMutableList()
        val i = list.indexOfFirst { it.uid == uid }
        if (i < 0) return
        list[i] = block(list[i])
        _group.value = _group.value.copy(people = list)
    }

    private fun dropLeg(uid: String) {
        val leg = legs.remove(uid) ?: return
        if (leg.callId.isNotBlank()) {
            scope.launch { signaling.hangup(leg.callId) }
            CallBus.dropIce(leg.callId)
        }
        runCatching { leg.pc?.close() }
        val rest = _group.value.people.filterNot { it.uid == uid }
        _group.value = _group.value.copy(people = rest)
        // Everyone has gone: there is no call left to be in.
        if (rest.isEmpty() && _group.value.active) leaveGroup()
    }

    private fun startGroupPolling() {
        groupPollJob?.cancel()
        groupPollJob = scope.launch {
            while (_group.value.active) {
                val r = signaling.poll(myUid(), waitMs = 15_000)
                val room = _group.value.roomId

                // Someone joining the group dials us. Answer silently.
                r.incoming
                    .filter { it.roomId.isNotBlank() && it.roomId == room }
                    .forEach { answerLeg(it) }

                legs.values.toList().forEach { leg ->
                    if (leg.callId.isBlank()) return@forEach

                    CallBus.collectIce(leg.callId).forEach { addLegIce(leg, it) }

                    r.answers[leg.callId]?.let { sdp ->
                        if (!leg.remoteSet) {
                            leg.pc?.setRemoteDescription(
                                object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        scope.launch {
                                            leg.remoteSet = true
                                            flushLegRemoteIce(leg)
                                        }
                                    }
                                },
                                SessionDescription(SessionDescription.Type.ANSWER, sdp),
                            )
                        }
                    }

                    r.ice[leg.callId]?.forEach { addLegIce(leg, it) }
                    if (leg.callId in r.ended) dropLeg(leg.uid)
                }
                // Only a guard against spinning when the server answers at once.
                delay(150)
            }
        }
    }

    private fun teardownGroup(error: String) {
        stopRingback()
        ringer.stop()
        audioWatch.stop()
        micDead = false
        micDetail = ""
        holdProximity(false)
        groupPollJob?.cancel(); groupPollJob = null
        legs.values.forEach { leg ->
            if (leg.callId.isNotBlank()) CallBus.dropIce(leg.callId)
            runCatching { leg.pc?.close() }
        }
        legs.clear()
        CallBus.callActive = false
        CallBus.videoCallLive = false
        stopLocalVideo()
        runCatching { audioSource?.dispose() }
        audioSource = null
        localTrack = null
        restoreAudioMode()
        _group.value = GroupInfo(error = error)
        scope.launch { refreshIceServers(signaling) }
    }

    private fun fail(message: String) {
        teardown(CallState.ENDED, message)
    }

    private fun teardown(state: CallState, error: String) {
        stopRingback()
        // Every way a call can end passes through here, and only answering it
        // stopped the ringtone. Declining, the caller giving up, or a failure
        // during setup all left the phone ringing and vibrating with nothing
        // on screen able to stop it.
        ringer.stop()
        ringWatch?.cancel(); ringWatch = null
        audioWatch.stop()
        micDead = false
        micDetail = ""
        holdProximity(false)
        CallBus.callActive = false
        // Stale candidates from this call must never leak into the next one.
        CallBus.dropIce(_call.value.callId)
        dialGen++
        scope.launch { refreshIceServers(signaling) }
        connectWatch?.cancel(); connectWatch = null
        runCatching { relay?.stop() }; relay = null
        pollJob?.cancel(); pollJob = null
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        stopLocalVideo()
        _remoteVideo.value = null
        runCatching { peer?.close() }
        peer = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        localTrack = null
        restoreAudioMode()
        _call.value = _call.value.copy(
            state = state, error = error,
            muted = false, speaker = false, video = false, remoteVideo = false, relay = false,
        )
    }

    /** Puts the phone's audio back the way the call found it. */
    @Suppress("DEPRECATION")
    private fun restoreAudioMode() {
        runCatching {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            // Hand playback back to whatever the call interrupted.
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    fun release() {
        if (_group.value.active) leaveGroup()
        teardown(CallState.IDLE, "")
        runCatching { factory?.dispose() }
        factory = null
    }

    companion object {
        /** Matches the server's own ring timeout, so both give up together. */
        private const val RING_TIMEOUT_MS = 45_000L

        /** Wideband stereo voice rather than Opus's narrow ~32 kbps default. */
        private const val AUDIO_BITRATE = 128_000

        /** Enough headroom for sharp 720p; WebRTC backs off on a poor link. */
        private const val VIDEO_BITRATE = 2_500_000
    }
}

/** Saves implementing all four SDP callbacks every time. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
