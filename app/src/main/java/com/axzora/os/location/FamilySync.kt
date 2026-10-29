package com.axzora.os.location

/**
 * A family member's most recent shared position.
 */
data class FamilyMember(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val updatedEpochMs: Long,
    val isSelf: Boolean = false,
    /** "person" or "vehicle" — the world map draws each differently. */
    val kind: String = "person",
    /** Compass bearing the entity is travelling, for pointing a car marker. */
    val heading: Float = 0f,
)

/**
 * Abstraction over the location-sharing backend.
 *
 * Family location sharing is CONSENT-BASED: each person installs the app and
 * turns sharing on for the same family code. This interface is the single seam
 * where a real backend plugs in.
 *
 * The bundled [LocalFamilySync] keeps everything on-device (no network) so the
 * app builds and runs out of the box. To sync across real devices, provide a
 * backend implementation (e.g. Firebase Realtime Database or your own server)
 * that reads/writes members under the family code. See README for the steps.
 */
interface FamilySync {
    /** Publish this device's position under [familyCode] for [displayName]. */
    suspend fun publish(familyCode: String, displayName: String, lat: Double, lon: Double)

    /** Fetch everyone currently sharing under [familyCode]. */
    suspend fun members(familyCode: String): List<FamilyMember>
}

/**
 * Offline default. Stores only this device's own position in memory, so the
 * map shows "you" and clearly indicates that no remote backend is connected.
 */
class LocalFamilySync : FamilySync {
    @Volatile private var self: FamilyMember? = null

    override suspend fun publish(familyCode: String, displayName: String, lat: Double, lon: Double) {
        self = FamilyMember(
            name = displayName.ifBlank { "You" },
            latitude = lat,
            longitude = lon,
            updatedEpochMs = System.currentTimeMillis(),
            isSelf = true,
        )
    }

    override suspend fun members(familyCode: String): List<FamilyMember> =
        listOfNotNull(self)
}
