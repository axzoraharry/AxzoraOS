package com.axzora.os.sms

/**
 * "Mr Happy Emergency Mode" — pick and auto-switch the best communication rail
 * for the current connectivity. Priority (Internet -> WiFi mesh -> Bluetooth ->
 * SMS -> QR), honouring what's actually available on the device.
 *
 * Dependency-free and unit-testable. The app feeds it live connectivity signals
 * and uses [adviseSwitch] to decide whether to re-route an outgoing message
 * (e.g. drop to SMS when the internet goes away, recover to internet when it
 * returns).
 */
class EmergencyRouter {

    /** Connectivity signals available to the router. */
    data class Connectivity(
        val online: Boolean = false,
        val wifiMesh: Boolean = false,
        val bluetooth: Boolean = false,
        val sms: Boolean = false,   // SIM + SEND/RECEIVE permission available
        val qr: Boolean = false,    // offline QR available
    )

    /** Available rails in order of priority (best first). */
    fun priority(conn: Connectivity): List<SmsGateway.CommsChannel> = buildList {
        if (conn.online) add(SmsGateway.CommsChannel.INTERNET)
        if (conn.wifiMesh) add(SmsGateway.CommsChannel.WIFI_MESH)
        if (conn.bluetooth) add(SmsGateway.CommsChannel.BLUETOOTH)
        if (conn.sms) add(SmsGateway.CommsChannel.SMS)
        if (conn.qr) add(SmsGateway.CommsChannel.QR)
    }

    /** The best rail currently available, or null if none. */
    fun bestRail(conn: Connectivity): SmsGateway.CommsChannel? = priority(conn).firstOrNull()

    /**
     * Whether to switch away from [current]. Returns the rail to move to, or
     * null if the current rail is already the best available (or nothing is
     * available). This auto-upgrades (e.g. Bluetooth -> Internet when online)
     * and auto-degrades (Internet -> SMS when the network drops).
     */
    fun adviseSwitch(
        conn: Connectivity,
        current: SmsGateway.CommsChannel,
    ): SmsGateway.CommsChannel? {
        val best = bestRail(conn) ?: return null
        return best.takeIf { it != current }
    }

    /**
     * Emergency-mode send: transmit [body] over the best available rail, and on
     * failure fall through the priority list until one channel accepts it.
     * [transmit] is the injected transport (e.g. internet HTTP -> SMS ->
     * Bluetooth). Returns the channel that sent, or null if none worked.
     */
    fun send(
        conn: Connectivity,
        body: String,
        transmit: (channel: SmsGateway.CommsChannel, body: String) -> Boolean,
    ): SmsGateway.CommsChannel? {
        for (ch in priority(conn)) {
            if (runCatching { transmit(ch, body) }.getOrDefault(false)) return ch
        }
        return null
    }
}
