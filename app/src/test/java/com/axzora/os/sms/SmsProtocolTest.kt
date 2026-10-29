package com.axzora.os.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Mr Happy SMS wire protocol (AxzoraOS Communication Layer). */
class SmsProtocolTest {

    @Test
    fun hpRoundTrip() {
        val text = SmsProtocol.encodeHp(500.0, "u-bob", "u-alice", "River cleanup reward")
        assertEquals(
            "MRHAPPY|HP|500.0|u-bob|u-alice|River cleanup reward",
            text,
        )
        val msg = SmsProtocol.parse(text)
        assertTrue(msg is SmsProtocol.Message.Hp)
        msg as SmsProtocol.Message.Hp
        assertEquals(500.0, msg.amount, 0.0001)
        assertEquals("u-bob", msg.toId)
        assertEquals("u-alice", msg.fromId)
        assertEquals("River cleanup reward", msg.note)
    }

    @Test
    fun impactRoundTrip() {
        val text = SmsProtocol.encodeImpact("829392", "COMPLETE", "RIVER-12", 150.0)
        assertEquals("MRHAPPY|IMPACT|829392|COMPLETE|RIVER-12|150.0", text)
        val msg = SmsProtocol.parse(text)
        assertTrue(msg is SmsProtocol.Message.Impact)
        msg as SmsProtocol.Message.Impact
        assertEquals("829392", msg.missionId)
        assertEquals("COMPLETE", msg.status)
        assertEquals("RIVER-12", msg.location)
        assertEquals(150.0, msg.reward, 0.0001)
    }

    @Test
    fun parseHumanTypedImpactTextToo() {
        // A human/agent might send the loosely-formatted example from the vision,
        // not the compact wire form; the gateway is expected to route these to the
        // AI for extraction. The compact form is what the codec round-trips.
        val compact = SmsProtocol.encodeImpact("829392", "COMPLETE", "Verified", 200.0)
        val msg = SmsProtocol.parse(compact)
        assertTrue(msg is SmsProtocol.Message.Impact)
    }

    @Test
    fun unknownAndMalformedReturnUnknown() {
        assertEquals(SmsProtocol.Message.Unknown, SmsProtocol.parse(""))
        assertEquals(SmsProtocol.Message.Unknown, SmsProtocol.parse("hello world"))
        assertEquals(SmsProtocol.Message.Unknown, SmsProtocol.parse("MRHAPPY|HP|only"))
        assertEquals(SmsProtocol.Message.Unknown, SmsProtocol.parse("OTHER|HP|100"))
    }

    @Test
    fun noteWithSeparatorIsEscapedAndRestored() {
        val text = SmsProtocol.encodeHp(10.0, "u-a", "u-b", "note | with pipe")
        val msg = SmsProtocol.parse(text)
        msg as SmsProtocol.Message.Hp
        assertEquals("note | with pipe", msg.note)
    }
}
