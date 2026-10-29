package com.axzora.os.gesture

/**
 * Minimal, self-contained hand-gesture / AirScript engine for AxzoraOS NRI (roadmap P1).
 *
 * This file is intentionally dependency-free (no CameraX or MediaPipe yet) so it
 * compiles in isolation. It models a 21-point hand skeleton (the MediaPipe Hands
 * convention) and classifies:
 *   - static gestures (open palm, fist/close, pinch, pointing)
 *   - AirScript shapes (a drawn 2D path matched against a registered command)
 *
 * Wire-up TODO (next step, in `MainActivity`/Camera source):
 *   1. Feed per-frame 21 hand landmarks from MediaPipe Hands (or a camera source)
 *      via [onFrame].
 *   2. Back gesture events into the existing [com.axzora.os.assistant.CommandEngine]
 *      action path (e.g. launch WhatsApp).
 */
class GestureEngine {

    /** One hand landmark in normalized [0,1] image coordinates (x right, y down). */
    data class Pt(val x: Double, val y: Double)

    /** 21 landmarks: MediaPipe Hands order (wrist=0, thumb tip=4, index tip=8, ...). */
    data class Hand(val lm: List<Pt>) {
        operator fun get(i: Int): Pt = lm[i]
        val wrist get() = this[0]
        val thumbTip get() = this[4]
        val indexTip get() = this[8]
        val middleTip get() = this[12]
        val pinkyTip get() = this[20]
    }

    enum class Gesture { OPEN_PALM, FIST, PINCH, POINTING, NONE }

    /** Called per-frame with one hand. Returns the current dominant gesture. */
    fun onFrame(hand: Hand): Gesture = classify(hand)

    fun classify(hand: Hand): Gesture {
        val spread = spreadOf(hand)
        val pinch = pinchRatio(hand)
        return when {
            pinch < 0.12f -> Gesture.PINCH
            spread < 0.10f -> Gesture.FIST
            spread > 0.55f -> Gesture.OPEN_PALM
            pointing(hand) -> Gesture.POINTING
            else -> Gesture.NONE
        }
    }

    /** Distance (normalized) from wrist to finger tips — a rough hand openness metric. */
    private fun spreadOf(h: Hand): Float {
        val fingers = listOf(h[4], h[8], h[12], h[16], h[20])
        val avg = fingers.fold(0.0) { a, p -> a + dist(h.wrist, p) } / fingers.size
        return avg.toFloat()
    }

    /** Thumb-tip to index-tip distance relative to wrist span -> 1 = pinch together. */
    private fun pinchRatio(h: Hand): Float {
        val d = dist(h.thumbTip, h.indexTip)
        val s = dist(h.wrist, h.middleTip).coerceAtLeast(1e-6)
        return (d / s).toFloat()
    }

    private fun pointing(h: Hand): Boolean {
        // Index extended, middle/ring/pinky tucked near palm.
        val indexExtended = dist(h.wrist, h.indexTip) > dist(h.wrist, h.middleTip) * 0.72
        val othersTucked = dist(h.wrist, h.pinkyTip) < dist(h.wrist, h.middleTip) * 0.55
        return indexExtended && othersTucked
    }

    private fun dist(a: Pt, b: Pt): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

/**
 * AirScript — the "gesture creation studio" lite.
 *
 * A user draws a shape in the air; we normalize its 2D trajectory into a fixed
 * feature vector and match it against a registered command table. This is the
 * "draw W -> open WhatsApp" idea: the recognizer is intentionally simple and
 * extensible so users can train their own shapes (extend with a stored template
 * set, or a tiny 1-NN classifier over normalized strokes).
 */
/**
 * AirScript — the "gesture creation studio" lite, using the classic "$1"
 * unistroke recognizer (resample -> indicative-angle rotation -> scale/translate
 * -> path distance). Robust to rotation, scale, and writing speed, so a drawn
 * shape reliably matches its learned template. For production-scale matching,
 * extend with a stored template set and the Protractor full-rotation search.
 */
class AirScript {

    data class Pt(val x: Double, val y: Double)

    /** A raw, unnormalized stroke from the air-writer (image coords). */
    data class Stroke(val points: List<Pair<Double, Double>>) {
        /** $1 normalization: resample, rotate by the indicative angle, scale to a square. */
        fun normalized(n: Int = 64): List<Pt> {
            val r = resample(points, n)
            if (r.isEmpty()) return emptyList()
            val cx = r.sumOf { it.x } / r.size
            val cy = r.sumOf { it.y } / r.size
            val first = r[0]
            val ang = kotlin.math.atan2(cy - first.y, cx - first.x)
            val cos = kotlin.math.cos(-ang); val sin = kotlin.math.sin(-ang)
            val rot = r.map { p ->
                val dx = p.x - cx; val dy = p.y - cy
                Pt(dx * cos - dy * sin, dx * sin + dy * cos)
            }
            val maxX = rot.maxOf { it.x }; val minX = rot.minOf { it.x }
            val maxY = rot.maxOf { it.y }; val minY = rot.minOf { it.y }
            val size = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1e-9)
            return rot.map { Pt(it.x / size, it.y / size) }
        }

        private fun resample(pts: List<Pair<Double, Double>>, n: Int): List<Pt> {
            if (pts.isEmpty()) return emptyList()
            if (pts.size < 2) return List(n) { Pt(pts[0].first, pts[0].second) }
            val seg = pts.zipWithNext().map { (a, b) ->
                kotlin.math.sqrt(
                    (b.first - a.first) * (b.first - a.first) +
                        (b.second - a.second) * (b.second - a.second),
                )
            }
            val total = seg.sum()
            if (total <= 1e-9) return List(n) { Pt(pts[0].first, pts[0].second) }
            val out = ArrayList<Pt>(n)
            var acc = 0.0; var segIdx = 0
            for (i in 0 until n) {
                val target = total * i / (n - 1).coerceAtLeast(1)
                while (segIdx < seg.size - 1 && acc + seg[segIdx] < target) {
                    acc += seg[segIdx]; segIdx++
                }
                val t = if (seg[segIdx] <= 1e-9) 0.0 else (target - acc) / seg[segIdx]
                val a = pts[segIdx]; val b = pts[segIdx + 1]
                out += Pt(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t)
            }
            return out
        }
    }

    /** A user-registered gesture command with its normalized template path. */
    data class Rule(val name: String, val template: List<Pt>, val action: () -> Unit)

    private val rules = mutableListOf<Rule>()

    /** Register a command: [name] + a sample stroke + the action to run. */
    fun learn(name: String, stroke: Stroke, action: () -> Unit) {
        rules += Rule(name, stroke.normalized(), action)
    }

    /** Match a drawn stroke via $1 path distance. Returns null if too far. */
    fun match(stroke: Stroke, threshold: Double = 0.14): Rule? {
        if (rules.isEmpty()) return null
        val q = stroke.normalized()
        val best = rules.minByOrNull { pathDistance(q, it.template) } ?: return null
        return best.takeIf { pathDistance(q, best.template) <= threshold }
    }

    /** Average nearest-neighbour distance between two normalized paths. */
    private fun pathDistance(a: List<Pt>, b: List<Pt>): Double {
        if (a.isEmpty() || b.isEmpty()) return 1.0
        val sum = a.sumOf { p ->
            b.minOf { q ->
                val dx = p.x - q.x; val dy = p.y - q.y; dx * dx + dy * dy
            }
        }
        return kotlin.math.sqrt(sum / a.size)
    }
}
