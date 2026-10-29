package com.axzora.os.gesture

import com.axzora.os.gesture.GestureEngine.Gesture
import com.axzora.os.gesture.GestureEngine.Hand
import com.axzora.os.gesture.GestureEngine.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the NRI gesture / AirScript engine (see roadmap P1). */
class GestureEngineTest {

    /** Build a Hand from explicit wrist + tips; other landmarks default to wrist. */
    private fun hand(wrist: Pt, vararg tips: Pt): Hand {
        val lm = MutableList(21) { wrist }
        // MediaPipe order: 0 wrist, 4 thumb tip, 8 index tip, 12 middle tip,
        // 16 ring tip, 20 pinky tip.
        val order = listOf(4 to 0, 8 to 1, 12 to 2, 16 to 3, 20 to 4)
        order.forEach { (idx, tipIdx) ->
            if (tipIdx < tips.size) lm[idx] = tips[tipIdx]
        }
        return Hand(lm)
    }

    // ---- GestureEngine ----

    @Test
    fun spreadOpenPalmClassifiesOpen() {
        // Wrist near (0.5,0.9); all five fingers spread far up.
        val w = Pt(0.5, 0.9)
        val open = hand(
            w,
            Pt(0.30, 0.40), // thumb
            Pt(0.40, 0.30), // index
            Pt(0.50, 0.25), // middle
            Pt(0.60, 0.30), // ring
            Pt(0.70, 0.40), // pinky
        )
        assertEquals(Gesture.OPEN_PALM, GestureEngine().classify(open))
    }

    @Test
    fun fingerTipsNearHandClassifyFist() {
        // All fingers tucked near the wrist => closed fist.
        val w = Pt(0.5, 0.5)
        val fist = hand(w, Pt(0.55, 0.52), Pt(0.52, 0.45), Pt(0.5, 0.45), Pt(0.48, 0.45), Pt(0.46, 0.5))
        assertEquals(Gesture.FIST, GestureEngine().classify(fist))
    }

    @Test
    fun thumbIndexTouchingClassifiesPinch() {
        val w = Pt(0.5, 0.7)
        // Thumb and index tips coincide; other fingers extended far.
        val pinch = hand(
            w,
            Pt(0.5, 0.45), Pt(0.5, 0.45), // thumb + index together
            Pt(0.5, 0.20), Pt(0.55, 0.25), Pt(0.45, 0.25),
        )
        assertEquals(Gesture.PINCH, GestureEngine().classify(pinch))
    }

    // ---- AirScript ----

    @Test
    fun airScriptMatchesLearnedShape() {
        val script = AirScript()
        val wStroke = AirScript.Stroke(
            listOf(
                0.2 to 0.7, 0.35 to 0.2, 0.5 to 0.7,
                0.65 to 0.2, 0.8 to 0.2, 0.8 to 0.7,
            ),
        )
        var fired = 0
        script.learn("W", wStroke) { fired++ }
        val match = script.match(wStroke)
        assertNotNull("Should match the learned 'W' shape", match)
        assertEquals("W", match!!.name)
        match.action()
        assertEquals(1, fired)
    }

    @Test
    fun airScriptNoMatchWhenFar() {
        val script = AirScript()
        val wStroke = AirScript.Stroke(listOf(0.2 to 0.7, 0.5 to 0.2, 0.8 to 0.7))
        script.learn("W", wStroke) { }
        // A totally different shape (a small dot/line cluster) should not match.
        val circle = AirScript.Stroke(
            List(20) { i ->
                val a = 2 * Math.PI * i / 20
                (0.5 + 0.02 * Math.cos(a)) to (0.5 + 0.02 * Math.sin(a))
            },
        )
        assertTrue("Different shape should not match 'W'", script.match(circle) == null)
    }

    @Test
    fun airScriptReturnsNullWhenNoRules() {
        assertNull(AirScript().match(AirScript.Stroke(listOf(0.1 to 0.1, 0.9 to 0.9))))
    }
}
