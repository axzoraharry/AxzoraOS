package com.axzora.os.car

import com.axzora.os.car.CarAwareness.Facility
import com.axzora.os.car.CarAwareness.FacilityKind
import com.axzora.os.car.CarAwareness.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Mr Happy car-awareness core. */
class CarAwarenessTest {

    // Car near a known location; facilities spread a few km apart around it.
    private val car = Pose(lat = 17.3850, lon = 78.4867, speedKmh = 32.0, headingDeg = 90f)

    private val facs = listOf(
        Facility("h1", "City Hospital", 17.3855, 78.4880, FacilityKind.HOSPITAL),
        Facility("a1", "Ambulance Hub", 17.3860, 78.4900, FacilityKind.AMBULANCE),
        Facility("f1", "Fire Station", 17.3810, 78.5000, FacilityKind.FIRE),
        Facility("p1", "Police", 17.3900, 78.4800, FacilityKind.POLICE),
    )

    @Test
    fun nearestHospitalAndAmbulanceAreCorrect() {
        assertEquals("h1", CarAwareness.nearestHospital(car, facs)?.id)
        assertEquals("a1", CarAwareness.nearestAmbulance(car, facs)?.id)
    }

    @Test
    fun nearestHonorsKindFilter() {
        assertEquals("f1", CarAwareness.nearest(car, facs, FacilityKind.FIRE)?.id)
    }

    @Test
    fun distanceIsSane() {
        val km = CarAwareness.distanceKm(17.385, 78.4867, 17.385, 78.4867)
        assertEquals(0.0, km, 0.0001)
        // ~0.001 deg lon at this latitude is roughly 0.1 km.
        assertTrue(CarAwareness.distanceKm(17.385, 78.4867, 17.385, 78.4877) in 0.05..0.2)
    }

    @Test
    fun emergencyDetectedWithinRadius() {
        // Hospital is ~130m away -> within 500m.
        assertTrue(CarAwareness.emergencyNearby(car, facs, radiusM = 500.0))
        // Police only is NOT emergency.
        assertFalse(CarAwareness.emergencyNearby(car, listOf(facs[3]), radiusM = 500.0))
    }

    @Test
    fun publishPayloadCarriesVehicleFields() {
        val p = CarAwareness.publishPayload(car, "CODE", "my-merc", "My Mercedes")
        assertEquals("CODE", p["code"])
        assertEquals("my-merc", p["deviceId"])
        assertEquals("vehicle", p["kind"])
        assertEquals(17.3850, p["lat"] as Double, 0.0001)
        assertEquals(32.0, p["speed"] as Double, 0.0001)
        assertEquals(90.0, p["heading"] as Double, 0.0001)
    }

    @Test
    fun routeSummaryFormats() {
        val s = CarAwareness.routeSummary(car, CarAwareness.nearestHospital(car, facs))
        assertTrue(s.contains("City Hospital"))
        assertTrue(s.contains("km away"))
    }

    @Test
    fun noFacilitiesReturnsNull() {
        assertNull(CarAwareness.nearestHospital(car, emptyList()))
    }
}
