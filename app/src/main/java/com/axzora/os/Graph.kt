package com.axzora.os

import com.axzora.os.location.HttpFamilySync

/**
 * Minimal process-wide service locator so the foreground
 * [com.axzora.os.location.LocationService] and the UI share one sync client.
 *
 * The server URL and device id are pushed in from the UI whenever settings
 * change, so the client always talks to the address the user configured.
 */
object Graph {

    @Volatile private var serverUrl: String = ""
    @Volatile private var deviceId: String = ""

    val familySync = HttpFamilySync(
        baseUrlProvider = { serverUrl },
        deviceIdProvider = { deviceId },
    )

    fun configure(serverUrl: String, deviceId: String) {
        this.serverUrl = serverUrl
        this.deviceId = deviceId
    }
}
