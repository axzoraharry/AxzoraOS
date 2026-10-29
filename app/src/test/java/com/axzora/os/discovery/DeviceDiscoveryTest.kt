package com.axzora.os.discovery

import com.axzora.os.discovery.DeviceDiscovery.Connection
import com.axzora.os.discovery.DeviceDiscovery.Device
import com.axzora.os.discovery.DeviceDiscovery.Kind
import com.axzora.os.discovery.DeviceDiscovery.Scan
import com.axzora.os.discovery.DeviceDiscovery.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Device Discovery Engine. */
class DeviceDiscoveryTest {

    private val devices = listOf(
        Device("mate-xt", "Huawei Mate XT", Kind.PHONE, Connection.BLE),
        Device("asus", "ASUS Laptop", Kind.LAPTOP, Connection.WIFI_LAN),
        Device("rpi", "Raspberry Pi", Kind.PI, Connection.WIFI_LAN),
        Device("nas", "My NAS", Kind.NAS, Connection.TAILSCALE),
        Device("merc", "Mercedes Node", Kind.MERCEDES, Connection.BLE),
        Device("room", "OpenRoom Gateway", Kind.GATEWAY, Connection.QR),
        Device("shady", "Unknown Radio", Kind.HOME, Connection.BLE),
    )

    @Test
    fun approvedDevicesAreMarked() {
        val scan = Scan(devices, approvals = setOf("mate-xt", "asus", "merc"))
        val results = DeviceDiscovery.classify(scan)
        assertEquals(3, results.count { it.status == Status.APPROVED })
        assertTrue(results.first { it.device.id == "merc" }.approved)
        assertFalse(results.first { it.device.id == "shady" }.approved)
    }

    @Test
    fun approvedFoundFiltersToApproved() {
        val scan = Scan(devices, approvals = setOf("mate-xt", "rpi"))
        assertEquals(listOf("mate-xt", "rpi"), DeviceDiscovery.approvedFound(scan).map { it.id })
    }

    @Test
    fun pairedDevicesAreAccepted() {
        val scan = Scan(devices, approvals = setOf("mate-xt"), paired = setOf("nas"))
        val nas = DeviceDiscovery.classify(scan).first { it.device.id == "nas" }
        assertEquals(Status.PAIRED, nas.status)
        assertTrue(nas.approved)
    }

    @Test
    fun summaryCounts() {
        val scan = Scan(devices, approvals = setOf("mate-xt", "asus", "merc"))
        val s = DeviceDiscovery.summary(scan)
        assertTrue(s.contains("3 approved"))
        assertTrue(s.contains("4 unknown"))
    }

    @Test
    fun fingerprintIsDeterministic() {
        val d = Device("x", "Y", Kind.PHONE, Connection.BLE)
        assertEquals(DeviceDiscovery.fingerprint(d), DeviceDiscovery.fingerprint(d))
    }
}
