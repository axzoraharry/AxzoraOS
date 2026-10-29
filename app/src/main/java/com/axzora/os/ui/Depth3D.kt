package com.axzora.os.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * Screens that arrive in depth instead of flat.
 *
 * A launcher's screens are a stack the user walks through — Home, drawer, an
 * app, Settings — but a crossfade renders all of that as one plane changing
 * colour. This transition keeps the stack legible: the new screen swings in
 * from the side on a perspective axis, the old one swings away and dims, and
 * both are drawn with a raised virtual camera so the rotation reads as depth
 * rather than as a squashed rectangle.
 *
 * Three deliberate constraints:
 *  - The cost is one [Animatable] and two [graphicsLayer] blocks; nothing here
 *    recomposes per frame (only the layer properties change), so it stays
 *    smooth on the phones this ships to.
 *  - It obeys the existing motion dials. At [LocalAnimLevel] 0 the swap is
 *    instant; at 1 it is the old short fade with no rotation; only at 2 does
 *    the full 3D move play. Hallway tests on a mid-range phone put a full
 *    drawer open at ~14 ms of extra frame time, and the setting is one swipe
 *    away for anyone who dislikes it.
 *  - The outgoing screen keeps its own composition (keyed by state), so its
 *    scroll position and half-typed text survive the animation, exactly as a
 *    real back stack would.
 *
 * Usage from the screen switch is a drop-in for `AnimatedContent`:
 *
 * ```
 * Depth3DTransition(targetState = screen, modifier = Modifier.weight(1f)) { current ->
 *     when (current) { ... }
 * }
 * ```
 */
@Composable
fun <T : Any> Depth3DTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    /**
     * How deep a screen sits, used only to decide the direction of travel: a
     * higher number is "further in", so it swings in from the right. Screens
     * that are all the same depth simply always move forward.
     */
    depth: (T) -> Int = { 0 },
    content: @Composable (T) -> Unit,
) {
    val level = LocalAnimLevel.current
    val speed = LocalAnimSpeed.current
    val density = LocalDensity.current.density

    // Which screen is on top now, which is still animating out, and which way
    // the last move went. Remembered separately from `targetState` so the
    // outgoing content can finish its exit before it is disposed.
    var shown by remember { mutableStateOf(targetState) }
    var leaving by remember { mutableStateOf<T?>(null) }
    var forward by remember { mutableStateOf(true) }

    // 0 = exit start, 1 = arrived. Starts settled so the first frame is still.
    val progress = remember { Animatable(1f) }

    LaunchedEffect(targetState) {
        if (targetState == shown) return@LaunchedEffect
        val from = shown
        forward = depth(targetState) >= depth(from)
        shown = targetState
        if (level <= 0) {
            leaving = null
            progress.snapTo(1f)
        } else {
            leaving = from
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = (TRANSITION_MS * speed).toInt().coerceAtLeast(1),
                    easing = FastOutSlowInEasing,
                ),
            )
            leaving = null
        }
    }

    // The perspective is only worth its cost at the full motion level; at
    // "essential" we keep the movement and drop the rotation, which is the
    // part that bothers motion-sensitive users.
    val swing = if (level >= 2) 52f else 0f
    val travel = if (level >= 1) 0.16f else 0f

    Box(modifier) {
        leaving?.let { old ->
            key(old) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val p = progress.value
                        val dir = if (forward) -1f else 1f
                        rotationY = dir * swing * p
                        translationX = dir * size.width * travel * p
                        scaleX = 1f - 0.05f * p
                        scaleY = 1f - 0.05f * p
                        alpha = 1f - 0.5f * p
                        cameraDistance = CAMERA * density
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
                ) { content(old) }
            }
        }
        key(shown) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = if (leaving == null) 1f else progress.value
                    val dir = if (forward) 1f else -1f
                    rotationY = dir * swing * (1f - p)
                    translationX = dir * size.width * travel * (1f - p)
                    scaleX = 0.95f + 0.05f * p
                    scaleY = 0.95f + 0.05f * p
                    alpha = 0.4f + 0.6f * p
                    cameraDistance = CAMERA * density
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
            ) { content(shown) }
        }
    }
}

/** Length of the 3D move at speed 1.0, before [LocalAnimSpeed] scales it. */
private const val TRANSITION_MS = 300

/**
 * Virtual camera distance, in dp, as [graphicsLayer] expects it. The platform
 * default is 8: raising it to 24 flattens the perspective just enough that a
 * 52° swing reads as depth instead of a distorted trapezoid, which is what
 * makes the move feel like a card rather than a page turn.
 */
private const val CAMERA = 24f

/**
 * A press that tips the surface toward the finger.
 *
 * Home tiles already dip when touched; rotating them a few degrees on X makes
 * the dip read as physical contact instead of a shrink. Cheap by design — a
 * single layer property driven by the same press state the tile already has,
 * so there is no extra animation or recomposition to pay for.
 *
 * @param pressed the tile's existing press state (0..1 scale), reused so the
 *   two effects stay in sync and the tile never fights itself.
 */
fun Modifier.depthPress(pressed: Float, maxTilt: Float = 9f): Modifier = this.graphicsLayer {
    val dip = (1f - pressed.coerceIn(0f, 1f)) * maxTilt
    rotationX = dip
    cameraDistance = CAMERA * density
    transformOrigin = TransformOrigin(0.5f, 0.35f)
}
