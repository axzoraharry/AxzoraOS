package com.axzora.os.security

/**
 * Real-time security monitor for the Mr Happy / Happy Paisa app — the
 * "Security First" engine shown in the UI (real-time biometric monitor,
 * Security Level, instant mismatch detection, auto-disconnect protection).
 *
 * Pure + dependency-free so it is unit-testable; the app feeds it live signals.
 */
object SecurityMonitor {

    /** Signals the monitor reasons over. */
    data class Signals(
        val biometricVerified: Boolean = false,
        val voiceIdentityMatched: Boolean = false,
        val deviceIntegrityOk: Boolean = false,
        val accountMismatch: Boolean = false,   // wallet/account anomaly
        val sessionAnomaly: Boolean = false,
    )

    /** A computed monitoring state. */
    data class State(
        val score: Int,               // 0..100 Security Level
        val healthy: Boolean,
        val autoDisconnect: Boolean,
        val reasons: List<String>,
    )

    /** Weighted security score in [0,100]. Mismatch is the strongest negative. */
    fun score(s: Signals): Int {
        var pct = 30 // baseline device + session trust
        if (s.biometricVerified) pct += 30
        if (s.voiceIdentityMatched) pct += 20
        if (s.deviceIntegrityOk) pct += 20
        if (s.accountMismatch) pct -= 45
        if (s.sessionAnomaly) pct -= 15
        return pct.coerceIn(0, 100)
    }

    fun state(s: Signals): State {
        val sc = score(s)
        val autoDisconnect = s.accountMismatch
        val reasons = buildList {
            if (!s.biometricVerified) add("biometric not verified")
            if (!s.voiceIdentityMatched) add("voice identity not matched")
            if (!s.deviceIntegrityOk) add("device integrity check failed")
            if (s.accountMismatch) add("account mismatch -> auto-disconnect")
            if (s.sessionAnomaly) add("session anomaly detected")
        }
        return State(sc, sc >= 60 && !autoDisconnect, autoDisconnect, reasons)
    }

    /** Voice readiness gate (the "voice recognition unavailable" case). */
    fun voiceReady(micPermission: Boolean, recognizerAvailable: Boolean): Boolean =
        micPermission && recognizerAvailable

    /** Voice readiness with a reason string, for the UI hint. */
    fun voiceStatus(micPermission: Boolean, recognizerAvailable: Boolean): Pair<Boolean, String> =
        when {
            !micPermission -> false to "Grant microphone permission"
            !recognizerAvailable -> false to "Speech recognition unavailable on this device"
            else -> true to "Ready"
        }
}
