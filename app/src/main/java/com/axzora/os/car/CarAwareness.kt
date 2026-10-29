package com.axzora.os.car

/**
 * Mr Happy car-awareness core: the rules that (a) know where the car is,
 * (b) find the nearest emergency/ambulance-capable facility, (c) decide whether
 * to raise an emergency signal, and (d) build the payload to broadcast to
 * everyone nearby (the family/live map /publish path).
 *
 * Dependency-free and unit-testable. Real inputs (GPS, hospitals/POI data,
 * Mercedes me client) live outside this file and drive [Pose]/[Facility].
 */
object CarAwareness {

    /** Car pose in WGS84. */
    data class Pose(
        val lat: Double,
        val lon: Double,
        val speedKmh: Double = 0.0,
        val headingDeg: Float = 0f,
    )

    enum class FacilityKind { HOSPITAL, AMBULANCE, FIRE, POLICE }

    /** An emergency-capable facility near the route. */
    data class Facility(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val kind: FacilityKind,
    )

    private val EMERGENCY = setOf(
        FacilityKind.HOSPITAL, FacilityKind.AMBULANCE, FacilityKind.FIRE,
    )

    /** Great-circle distance in km (haversine). */
    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val s = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(aLat)) * kotlin.math.cos(Math.toRadians(bLat)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(s))
    }

    /** Nearest facility, optionally of a specific kind. */
    fun nearest(car: Pose, facilities: List<Facility>, kind: FacilityKind? = null): Facility? =
        facilities.filter { kind == null || it.kind == kind }
            .minByOrNull { distanceKm(car.lat, car.lon, it.lat, it.lon) }

    fun nearestHospital(car: Pose, facilities: List<Facility>): Facility? =
        nearest(car, facilities, FacilityKind.HOSPITAL)

    fun nearestAmbulance(car: Pose, facilities: List<Facility>): Facility? =
        nearest(car, facilities, FacilityKind.AMBULANCE)

    /** True if any emergency facility is within [radiusM] of the car. */
    fun emergencyNearby(car: Pose, facilities: List<Facility>, radiusM: Double): Boolean =
        facilities.any { it.kind in EMERGENCY && distanceKm(car.lat, car.lon, it.lat, it.lon) * 1000.0 <= radiusM }

    /** The publish payload to broadcast the car to everyone nearby (matches /publish). */
    fun publishPayload(
        car: Pose,
        familyCode: String,
        deviceId: String,
        name: String = "My Mercedes",
    ): Map<String, Any?> = mapOf(
        "code" to familyCode,
        "deviceId" to deviceId,
        "name" to name,
        "lat" to car.lat,
        "lon" to car.lon,
        "kind" to "vehicle",
        "heading" to car.headingDeg.toDouble(),
        "speed" to car.speedKmh,
    )

    /** Human-readable "show the way" summary for the active destination. */
    fun routeSummary(car: Pose, dest: Facility?): String {
        val d = dest ?: return "No destination set."
        val km = distanceKm(car.lat, car.lon, d.lat, d.lon)
        return "${d.name} (${d.kind}) — ${"%.1f".format(km)} km away"
    }
}
