package com.axzora.os.sms

import com.axzora.os.sms.SmsGateway.CommsChannel
import com.axzora.os.sms.SmsGateway.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Mr Happy Communication Layer gateway. */
class SmsGatewayTest {

    @Test
    fun inboundHpMessageBanksMoney() {
        val gateway = SmsGateway()
        val sms = SmsGateway.Sms(
            "+915555000111",
            SmsProtocol.encodeHp(50.0, "u-bob", "u-alice", "Cleanup reward"),
        )
        val d = gateway.handle(sms)
        assertTrue(d is Decision.BankHp)
        d as Decision.BankHp
        assertEquals(50.0, d.amount, 0.0001)
        assertEquals("u-alice", d.from)
        assertEquals("Cleanup reward", d.note)
    }

    @Test
    fun inboundImpressionCompleteRewards() {
        val gateway = SmsGateway()
        val d = gateway.handle(
            SmsGateway.Sms("+915555000222", SmsProtocol.encodeImpact("829392", "COMPLETE", "RIVER-12", 150.0)),
        )
        assertTrue(d is Decision.Reward)
        d as Decision.Reward
        assertEquals("829392", d.missionId)
        assertEquals("RIVER-12", d.location)
        assertEquals(150.0, d.amount, 0.0001)
    }

    @Test
    fun incompleteImpressionIsIgnored() {
        val gateway = SmsGateway()
        val d = gateway.handle(
            SmsGateway.Sms("+915555000222", SmsProtocol.encodeImpact("829392", "PENDING", "RIVER-12", 0.0)),
        )
        assertEquals(Decision.Ignore, d)
    }

    @Test
    fun unknownSmsIsIgnored() {
        assertEquals(Decision.Ignore, SmsGateway().handle(SmsGateway.Sms("+1", "not a happy pasi sms")))
    }

    @Test
    fun sideEffectCallbacksFire() {
        var banked: SmsGateway.SysBankHp? = null
        var reward: SmsGateway.SysReward? = null
        val gateway = SmsGateway(onBankHp = { banked = it }, onReward = { reward = it })
        gateway.handle(SmsGateway.Sms("+1", SmsProtocol.encodeHp(5.0, "u-a", "u-b")))
        gateway.handle(SmsGateway.Sms("+1", SmsProtocol.encodeImpact("m1", "COMPLETE", "K", 25.0)))
        assertEquals(5.0, banked?.amount ?: 0.0, 0.0001)
        assertEquals(25.0, reward?.amount ?: 0.0, 0.0001)
    }

    @Test
    fun fallbackOrderPrefersAvailableHigherRails() {
        val g = SmsGateway()
        assertEquals(
            listOf(CommsChannel.INTERNET, CommsChannel.BLUETOOTH, CommsChannel.SMS, CommsChannel.QR),
            g.fallbackOrder(online = true, wifiMesh = false, bluetooth = true),
        )
        assertEquals(CommsChannel.SMS, g.primaryRail(online = false, wifiMesh = false, bluetooth = false))
        assertEquals(CommsChannel.INTERNET, g.primaryRail(online = true, wifiMesh = false, bluetooth = false))
    }

    @Test
    fun sendBuildsAndTransmits() {
        val g = SmsGateway()
        val body = g.buildTransferSms(120.0, "u-c", "u-b", "Thanks")
        assertEquals("MRHAPPY|HP|120.0|u-c|u-b|Thanks", body)
        var sent = ""
        val ok = g.send("+915555000999", body) { number, text -> sent = "$number::$text"; true }
        assertTrue(ok)
        assertTrue(sent.startsWith("+915555000999::MRHAPPY|HP|120.0"))
    }

    @Test
    fun sendRejectsBlankAndPropagatesTransportFailure() {
        val g = SmsGateway()
        assertEquals(false, g.send("", "MRHAPPY|HP|1|x|y") { _, _ -> true })
        assertEquals(false, g.send("+1", "") { _, _ -> true })
        // transport throws -> returns false
        assertEquals(false, g.send("+1", "MRHAPPY|HP|1|x|y") { _, _ -> throw RuntimeException("no sim") })
    }
}
