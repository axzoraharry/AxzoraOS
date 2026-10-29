package com.axzora.os.sms

import com.axzora.os.sms.SmsGateway.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the SmsActionSink bridge. */
class SmsActionSinkTest {

    @Test
    fun bankHpDecisionInvokesBankCallback() {
        var banked: Triple<Double, String, String>? = null
        val sink = SmsActionSink(
            bankHp = { a, f, n -> banked = Triple(a, f, n) },
            reward = { _, _, _ -> },
        )
        val handled = sink.apply(Decision.BankHp(50.0, "u-alice", "Cleanup"))
        assertTrue(handled)
        assertEquals(50.0, banked?.first ?: 0.0, 0.0001)
        assertEquals("u-alice", banked?.second)
        assertEquals("Cleanup", banked?.third)
    }

    @Test
    fun rewardDecisionInvokesRewardCallback() {
        var reward: Triple<String, String, Double>? = null
        val sink = SmsActionSink(
            bankHp = { _, _, _ -> },
            reward = { m, l, a -> reward = Triple(m, l, a) },
        )
        assertTrue(sink.apply(Decision.Reward("829392", "RIVER-12", 150.0)))
        assertEquals("829392", reward?.first)
        assertEquals("RIVER-12", reward?.second)
        assertEquals(150.0, reward?.third ?: 0.0, 0.0001)
    }

    @Test
    fun ignoreDecisionDoesNothing() {
        val sink = SmsActionSink(bankHp = { _, _, _ -> }, reward = { _, _, _ -> })
        assertEquals(false, sink.apply(Decision.Ignore))
    }

    @Test
    fun processRoutesAnInboundSmsEndToEnd() {
        var banked = 0.0
        val sink = SmsActionSink(
            bankHp = { a, _, _ -> banked = a },
            reward = { _, _, _ -> },
        )
        val ok = sink.process(SmsGateway.Sms("+91", SmsProtocol.encodeHp(75.0, "u-x", "u-y", "note")))
        assertTrue(ok)
        assertEquals(75.0, banked, 0.0001)
    }
}
