package com.axzora.os.sms

import com.axzora.os.sms.EmergencyRouter.Connectivity
import com.axzora.os.sms.SmsGateway.CommsChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the Mr Happy Emergency Mode rail router. */
class EmergencyRouterTest {

    private val r = EmergencyRouter()

    @Test
    fun bestRailPrefersInternetWhenOnline() {
        val c = Connectivity(online = true, sms = true, qr = true)
        assertEquals(CommsChannel.INTERNET, r.bestRail(c))
    }

    @Test
    fun noNetworkDegradesToSms() {
        val c = Connectivity(wifiMesh = false, bluetooth = false, sms = true)
        assertEquals(CommsChannel.SMS, r.bestRail(c))
    }

    @Test
    fun noRailAtAllReturnsNull() {
        assertNull(r.bestRail(Connectivity()))
    }

    @Test
    fun adviseSwitchDegradesFromInternetToSms() {
        val c = Connectivity(online = false, bluetooth = false, sms = true, qr = true)
        assertEquals(CommsChannel.SMS, r.adviseSwitch(c, CommsChannel.INTERNET))
    }

    @Test
    fun adviseSwitchUpgradesFromBluetoothToInternet() {
        val c = Connectivity(online = true, wifiMesh = true)
        assertEquals(CommsChannel.INTERNET, r.adviseSwitch(c, CommsChannel.BLUETOOTH))
    }

    @Test
    fun adviseSwitchNullWhenAlreadyBest() {
        val c = Connectivity(online = true)
        assertNull(r.adviseSwitch(c, CommsChannel.INTERNET))
    }

    @Test
    fun priorityOrdersByNetworkPreference() {
        val c = Connectivity(online = true, wifiMesh = true, bluetooth = true, sms = true, qr = true)
        assertEquals(
            listOf(CommsChannel.INTERNET, CommsChannel.WIFI_MESH, CommsChannel.BLUETOOTH, CommsChannel.SMS, CommsChannel.QR),
            r.priority(c),
        )
    }

    @Test
    fun sendFallsBackToNextRailOnFailure() {
        val c = Connectivity(online = false, bluetooth = true, sms = true, qr = true)
        // Internet unavailable; Bluetooth fails; SMS is the one that sends.
        val used = r.send(c, "MRHAPPY|HP|1|x|y") { ch, _ -> ch == CommsChannel.SMS }
        assertEquals(CommsChannel.SMS, used)
    }

    @Test
    fun sendUsesInternetWhenItWorks() {
        val c = Connectivity(online = true, sms = true)
        val used = r.send(c, "hi") { ch, _ -> ch == CommsChannel.INTERNET }
        assertEquals(CommsChannel.INTERNET, used)
    }

    @Test
    fun sendReturnsNullWhenAllRailsFail() {
        val c = Connectivity(online = true, bluetooth = true, sms = true)
        assertNull(r.send(c, "hi") { _, _ -> false })
    }
}
