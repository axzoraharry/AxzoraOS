package com.axzora.os.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hands an incoming call from the background service to the UI.
 *
 * The service runs whether or not an Activity exists, so the two cannot talk
 * through the composition. This is the single place they meet.
 */
object CallBus {

    private val _pending = MutableStateFlow<IncomingCall?>(null)
    val pending: StateFlow<IncomingCall?> = _pending

    /** True while an Activity is on screen and able to show the call itself. */
    @Volatile var uiVisible: Boolean = false

    /**
     * True while a call is being set up or in progress.
     *
     * The server hands each ICE candidate out exactly once, to whichever
     * request drains it first. While a call is live only [CallManager] may
     * poll — a background poll landing first would swallow candidates the
     * call needs, and the loss shows up as calls that connect slowly or by a
     * worse route.
     */
    @Volatile var callActive: Boolean = false

    /**
     * ICE candidates drained by a poll that wasn't the call engine's.
     *
     * The caller starts streaming its network routes the moment it dials —
     * while the callee is still ringing and only the background watcher is
     * polling. The server hands each candidate out exactly once, so whatever
     * the watcher drains would otherwise be lost, and the call is left to
     * rediscover those routes the slow way. Every poller banks what it
     * drains here; the call engine collects it when the call is answered.
     */
    private val iceBuffer = HashMap<String, MutableList<IceCandidate>>()

    fun bankIce(callId: String, candidates: List<IceCandidate>) {
        if (candidates.isEmpty()) return
        synchronized(iceBuffer) {
            iceBuffer.getOrPut(callId) { mutableListOf() }.addAll(candidates)
        }
    }

    fun collectIce(callId: String): List<IceCandidate> = synchronized(iceBuffer) {
        iceBuffer.remove(callId) ?: emptyList()
    }

    /** Forget candidates for calls that are over. */
    fun dropIce(callId: String) {
        synchronized(iceBuffer) { iceBuffer.remove(callId) }
    }

    /**
     * True while a call with live video is in progress.
     *
     * The Activity needs this the instant the user leaves — to shrink into a
     * floating window instead of vanishing — and it can't read the
     * composition's state from a lifecycle callback, so the composition
     * mirrors it here.
     */
    @Volatile var videoCallLive: Boolean = false

    /** True while the call is showing as a small floating window. */
    private val _pip = MutableStateFlow(false)
    val pip: StateFlow<Boolean> = _pip

    fun setPip(on: Boolean) { _pip.value = on }

    fun offer(call: IncomingCall) { _pending.value = call }

    fun clear() { _pending.value = null }

    /** Set by the service so the UI can answer with the right media type. */
    @Volatile var answerWithVideo: Boolean = false
}
