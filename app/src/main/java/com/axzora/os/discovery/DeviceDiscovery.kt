package com.axzora.os.discovery

/**
 * Device Discovery Engine — the real layer behind "Looking for your devices…".
 *
 * Given a scan of nearby devices (BLE / WiFi LAN / QR / USB / Tailscale) and an
 * approved-ecosystem allowlist, it classifies each as APPROVED / PAIRED /
 * UNKNOWN and produces the "Scanning approved ecosystem… Found: ✓ …" summary.
 *
 * Pure + dependency-free so it is unit-testable; the app supplies the scan
 * (from NearbyManager / Bluetooth / ServerFinder) and the approvals allowlist.
 */
object DeviceDiscovery {

    data class Device(
        val id: String,
        val name: String,
        val kind: Kind,
        val connection: Connection,
    )

    enum class Kind { PHONE, LAPTOP, PI, NAS, MERCEDES, GATEWAY, HOME }
    enum class Connection { BLE, WIFI_LAN, QR, USB, TAILSCALE }
    enum class Status { APPROVED, PAIRED, UNKNOWN }

    data class Result(val device: Device, val status: Status, val approved: Boolean)

    /** One scan snapshot: what was found + the allowlist of approved ids. */
    data class Scan(val found: List<Device>, val approvals: Set<String>, val paired: Set<String> = emptySet())

    /** Classify every found device against the approvals + paired sets. */
    fun classify(scan: Scan): List<Result> = scan.found.map { d ->
        when {
            d.id in scan.approvals -> Result(d, Status.APPROVED, true)
            d.id in scan.paired -> Result(d, Status.PAIRED, true)
            else -> Result(d, Status.UNKNOWN, false)
        }
    }

    /** All approved devices (the "✓ found" list). */
    fun approvedFound(scan: Scan): List<Device> = classify(scan).filter { it.approved }.map { it.device }

    /** Human summary line for the UI. */
    fun summary(scan: Scan): String {
        val r = classify(scan)
        val ok = r.count { it.approved }
        val unk = r.count { !it.approved }
        return "Found ${r.size} device(s): $ok approved, $unk unknown"
    }

    /** A fingerprinted id for one device (e.g. hash of serial + kind) — placeholder. */
    fun fingerprint(device: Device, salt: String = ""): String =
        (device.id + device.kind.name + salt).hashCode().toString()
}
