package com.axzora.os.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.math.BigInteger

/**
 * What the audio path is actually doing, as opposed to what the call state
 * claims it is doing.
 *
 * A WebRTC call reports itself connected as soon as ICE finds a route. That
 * says nothing about whether a microphone opened, whether packets are leaving,
 * or whether any are arriving — which is why "connected, and one side hears
 * nothing" is the classic call bug and the hardest to chase: everything on
 * screen looks right.
 */
data class AudioHealth(
    /** The microphone is producing sound. False means the far end hears silence. */
    val micLive: Boolean = true,
    /** Audio packets are leaving this phone. */
    val sending: Boolean = true,
    /** Audio packets are arriving from the far end. */
    val receiving: Boolean = true,
    /** host = direct, srflx = through NAT, relay = via the TURN server. */
    val route: String = "",
    /** "Wi-Fi" or "mobile data", as Android reports it. */
    val network: String = "",
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    /** Blank while healthy; otherwise what to tell the user, in their terms. */
    val warning: String = "",
) {
    /**
     * One line describing the call, meant to be read off the screen or
     * screenshotted. An intermittent fault reported as "it happened again"
     * cannot be chased; the same fault reported as "relay, mobile data, sent
     * 0" is a five-minute fix.
     */
    val summary: String
        get() = buildList {
            if (route.isNotBlank()) add(route)
            if (network.isNotBlank()) add(network)
            add("sent $packetsSent")
            add("recv $packetsReceived")
            if (!micLive) add("mic silent")
        }.joinToString(" · ")
}

/**
 * Samples WebRTC's own counters during a call and works out, when something is
 * wrong, *which direction* is wrong.
 *
 * The three faults it separates are the three the user can actually report:
 * the microphone never opened (they hear nothing), nothing is leaving the
 * phone (they hear nothing, for a different reason), and nothing is arriving
 * (you hear nothing). Guessing between those from a bug report is the reason
 * one-way audio takes weeks to find.
 */
class AudioWatch(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _health = MutableStateFlow(AudioHealth())
    val health: StateFlow<AudioHealth> = _health

    private var job: Job? = null

    /**
     * @param peer read fresh each sample, because a call can be torn down
     *   between one sample and the next.
     * @param muted deliberate silence must not be reported as a broken
     *   microphone.
     */
    fun start(peer: () -> PeerConnection?, muted: () -> Boolean) {
        job?.cancel()
        _health.value = AudioHealth()
        job = scope.launch {
            var lastSent = -1L
            var lastReceived = -1L
            var lastEnergy = -1.0
            var sendStalls = 0
            var receiveStalls = 0
            var micStalls = 0

            while (true) {
                delay(SAMPLE_MS)
                val pc = peer() ?: break
                val report = withTimeoutOrNull(SAMPLE_MS) { pc.stats() } ?: continue

                val sent = report.audioCounter("outbound-rtp", "packetsSent")
                val received = report.audioCounter("inbound-rtp", "packetsReceived")
                val energy = report.micEnergy()
                val (route, _) = report.chosenRoute()

                // First sample only establishes a baseline — every counter
                // looks stalled when there is nothing to compare it against.
                if (lastSent >= 0) {
                    // Opus is not configured for discontinuous transmission,
                    // so packets flow whether or not anyone is speaking. A
                    // count that stops moving means the media path is gone,
                    // not that the room went quiet.
                    sendStalls = if (sent > lastSent) 0 else sendStalls + 1
                    receiveStalls = if (received > lastReceived) 0 else receiveStalls + 1

                    // Energy is cumulative and includes the room's noise
                    // floor, which is never exactly zero on a working
                    // microphone. Exactly no growth, held for a long window,
                    // is a microphone that never opened — not a quiet room.
                    micStalls = when {
                        muted() -> 0
                        energy > lastEnergy -> 0
                        else -> micStalls + 1
                    }
                }
                lastSent = sent
                lastReceived = received
                lastEnergy = energy

                val micLive = micStalls < MIC_SAMPLES
                val sending = sendStalls < STALL_SAMPLES
                val receiving = receiveStalls < STALL_SAMPLES

                _health.value = AudioHealth(
                    micLive = micLive,
                    sending = sending,
                    receiving = receiving,
                    route = route,
                    network = network(),
                    packetsSent = sent,
                    packetsReceived = received,
                    // Ordered by what the user can act on. A dead microphone
                    // is worth saying plainly, because it is usually another
                    // app holding it and hanging up fixes it.
                    warning = when {
                        !micLive ->
                            "They can't hear you — your microphone isn't picking anything up. " +
                                "Close any other app that might be using it."
                        !sending && !receiving ->
                            "Audio has stopped in both directions — the connection dropped."
                        !sending ->
                            "They can't hear you — audio isn't leaving this phone."
                        !receiving ->
                            "You can't hear them — no audio is arriving."
                        else -> ""
                    },
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _health.value = AudioHealth()
    }

    /** Wi-Fi or mobile data — the first thing asked about any call fault. */
    private fun network(): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return ""
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> ""
        }
    }.getOrDefault("")

    companion object {
        private const val SAMPLE_MS = 2_000L

        /** Eight seconds of no packets. Long enough to ride out a hiccup. */
        private const val STALL_SAMPLES = 4

        /**
         * Twenty seconds of a microphone reporting exactly nothing. Longer
         * than the packet windows on purpose: a wrong "your mic is broken"
         * during a pause in the conversation would be worse than saying
         * nothing at all.
         */
        private const val MIC_SAMPLES = 10
    }
}

// ------------------------------------------------------------------ stats

/** Bridges WebRTC's callback-style stats onto the sampling coroutine. */
private suspend fun PeerConnection.stats(): RTCStatsReport? =
    suspendCancellableCoroutine { cont ->
        runCatching {
            getStats { report -> if (cont.isActive) cont.resumeWith(Result.success(report)) }
        }.onFailure {
            if (cont.isActive) cont.resumeWith(Result.success(null))
        }
    }

/**
 * Counters arrive as whichever numeric type the native layer chose — long,
 * integer or big integer depending on the field and the build. Reading them
 * by type is how a stats reader silently returns zero forever.
 */
private fun Any?.asLong(): Long = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is BigInteger -> this.toLong()
    is Double -> this.toLong()
    is Number -> this.toLong()
    else -> 0L
}

private fun Any?.asDouble(): Double = (this as? Number)?.toDouble() ?: 0.0

/** Sums [field] across every audio stream of [type]. */
private fun RTCStatsReport.audioCounter(type: String, field: String): Long =
    statsMap.values
        .filter { it.type == type && it.members["kind"] == "audio" }
        .sumOf { it.members[field].asLong() }

/**
 * Cumulative energy the microphone has captured.
 *
 * Taken from the media source rather than the outbound stream: the source is
 * the microphone itself, so it distinguishes "the mic is dead" from "the mic
 * is fine but nothing is being sent", which are different faults with
 * different fixes.
 */
private fun RTCStatsReport.micEnergy(): Double =
    statsMap.values
        .filter { it.type == "media-source" && it.members["kind"] == "audio" }
        .sumOf { it.members["totalAudioEnergy"].asDouble() }

/** The candidate pair actually carrying media, and the local address it uses. */
private fun RTCStatsReport.chosenRoute(): Pair<String, String> {
    val pair = statsMap.values.firstOrNull {
        it.type == "candidate-pair" &&
            (it.members["nominated"] == true || it.members["state"] == "succeeded")
    } ?: return "" to ""
    val localId = pair.members["localCandidateId"] as? String ?: return "" to ""
    val local = statsMap[localId] ?: return "" to ""
    val type = local.members["candidateType"] as? String ?: ""
    // "relay" is worth naming: it means no direct route existed and the call
    // is going through the TURN server, which is the state most likely to be
    // sitting behind an intermittent fault.
    val label = when (type) {
        "host" -> "direct"
        "srflx", "prflx" -> "through NAT"
        "relay" -> "relay"
        else -> type
    }
    return label to ((local.members["ip"] as? String) ?: "")
}
