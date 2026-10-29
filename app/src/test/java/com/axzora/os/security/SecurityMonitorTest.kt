package com.axzora.os.security

import com.axzora.os.security.SecurityMonitor.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the SecurityMonitor. */
class SecurityMonitorTest {

    @Test
    fun fullyVerifiedScoresHigh() {
        val s = Signals(biometricVerified = true, voiceIdentityMatched = true, deviceIntegrityOk = true)
        assertEquals(100, SecurityMonitor.score(s))
        assertTrue(SecurityMonitor.state(s).healthy)
        assertFalse(SecurityMonitor.state(s).autoDisconnect)
    }

    @Test
    fun accountMismatchTriggersAutoDisconnect() {
        val s = Signals(biometricVerified = true, voiceIdentityMatched = true, deviceIntegrityOk = true, accountMismatch = true)
        val st = SecurityMonitor.state(s)
        assertTrue(st.autoDisconnect)
        assertFalse(st.healthy)
        assertTrue(st.reasons.any { it.contains("auto-disconnect") })
    }

    @Test
    fun nothingVerifiedIsLowScoreAndUnhealthy() {
        val st = SecurityMonitor.state(Signals())
        assertTrue(st.score < 60)
        assertFalse(st.healthy)
        assertTrue(st.reasons.any { it.contains("biometric not verified") })
    }

    @Test
    fun voiceReadyGatesOnMicPermissionAndRecognizer() {
        assertFalse(SecurityMonitor.voiceReady(micPermission = false, recognizerAvailable = true))
        assertFalse(SecurityMonitor.voiceReady(micPermission = true, recognizerAvailable = false))
        assertTrue(SecurityMonitor.voiceReady(micPermission = true, recognizerAvailable = true))
    }

    @Test
    fun voiceStatusGivesUiHint() {
        val (ok, msg) = SecurityMonitor.voiceStatus(micPermission = false, recognizerAvailable = true)
        assertFalse(ok)
        assertTrue(msg.contains("Grant microphone"))
    }
}
