package com.axzora.os.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The one press interaction the whole shell shares: a springy scale-dip with a
 * light haptic tick, gated by the Animations setting. Centralised here so every
 * tappable surface — chips, the dock, the assistant bar, tiles — feels the same
 * instead of some responding and some being dead to the touch.
 */
@Composable
fun Modifier.pressBounce(
    pressedScale: Float = 0.9f,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val anim = LocalAnimLevel.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && anim > 0) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "pressBounce",
    )
    val hf = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null) {
            if (haptic && anim > 0) runCatching { hf.performHapticFeedback(HapticFeedbackType.LongPress) }
            onClick()
        }
}

/**
 * A one-shot entry reveal: fade + rise, delayed by [index] so a row or grid
 * cascades in. Only plays at the Full animation level; at Off/Simple the item
 * is simply present. Reads [LocalAnimSpeed] so it honours the speed setting.
 */
@Composable
fun Modifier.revealFrom(index: Int, rise: Float = 22f): Modifier = composed {
    val anim = LocalAnimLevel.current
    val speed = LocalAnimSpeed.current
    var shown by remember { mutableStateOf(anim < 2) }
    LaunchedEffect(Unit) {
        if (!shown) { delay(((index % 20) * 34 * speed).toLong()); shown = true }
    }
    val t by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween((300 * speed).toInt().coerceAtLeast(1)),
        label = "reveal",
    )
    this.graphicsLayer { alpha = t; translationY = (1f - t) * rise }
}

/**
 * Subtle depth from the phone's motion: the surface shifts a few pixels the
 * other way to a tilt, like peering into a diorama. Reads the rotation-vector
 * (falling back to the accelerometer) and is disabled when the Animations
 * setting is off. Best-effort — devices without a usable sensor just stay put.
 */
@Composable
fun Modifier.tiltParallax(amplitude: Dp = 7.dp): Modifier {
    val context = LocalContext.current
    val anim = LocalAnimLevel.current
    var tx by remember { mutableStateOf(0f) }
    var ty by remember { mutableStateOf(0f) }
    val amp = with(LocalDensity.current) { amplitude.toPx() }
    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (anim <= 0) { tx = 0f; ty = 0f; return }
                tx = (e.values[0] / 6f).coerceIn(-1f, 1f)
                ty = (e.values[1] / 6f).coerceIn(-1f, 1f)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.also { sm?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sensor?.also { sm?.unregisterListener(listener, it) } }
    }
    return this.graphicsLayer { translationX = -tx * amp; translationY = -ty * amp }
}

/**
 * The room the app sits in: a layered aurora — a warm horizon glow low on the
 * screen, two slow drifting light-pools in complementary hues, a field of
 * gently rising motes, and a fine grain over everything.
 *
 * The grain matters more than it sounds. Flat digital gradients are what make
 * an interface read as generic; a little noise gives the surface a material
 * quality and hides the banding that large gradients show on OLED panels.
 * Both the dust field and the mote field are generated once and reused.
 *
 * Motion respects the Animations setting: at level 0 the light-pools and motes
 * hold still (only the static painting remains), so "no motion" really means it.
 */
@Composable
fun AuroraBackground(
    accent: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
    atmosphere: Atmosphere? = null,
    content: @Composable () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    // A painted atmosphere replaces the base and lends its glow colour; the
    // rest of the recipe — highlight, vignette, grain — stays identical, so
    // switching skies never changes the app's character.
    val painted = atmosphere?.takeIf { it.top != Color.Unspecified }
    val glow = painted?.glow ?: accent
    // A second, cooler pool sits behind the accent one so the light reads as a
    // real gradient of colour rather than a single flat tint.
    val glow2 = lerp(glow, if (dark) Color(0xFF4F86FF) else Color(0xFF7FB0FF), 0.6f)

    val moving = LocalAnimLevel.current > 0

    // One master phase; each element reads it at a different rate/offset so the
    // scene never repeats obviously. Held at 0 when motion is off.
    val phase = if (moving) {
        rememberInfiniteTransition(label = "atmosphere").animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                tween(52_000, easing = LinearEasing), RepeatMode.Restart,
            ),
            label = "drift",
        ).value
    } else 0f

    // "Breath": the light-pools gently pulse so the scene never feels frozen,
    // even at a glance. Reads the same master phase.
    val breath = 0.86f + 0.14f * sin((phase + 0.4) * 1.3).toFloat()

    // Dynamic light: the room's glow slowly leans between its warm accent and
    // the cooler complement over a long cycle, so the temperature of the scene
    // shifts as the day goes rather than staying a single flat colour.
    val dynamic = lerp(glow, glow2, (0.5f + 0.5f * sin(phase * 0.4 + 0.9).toFloat()) * 0.4f)

    // Fixed seeds: grain and motes should look like properties of the surface,
    // not something that re-scatters on every recomposition.
    val grain = remember {
        val rnd = Random(7)
        List(300) { Triple(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
    }
    val motes = remember {
        val rnd = Random(19)
        // x, baseY, size, speed
        List(34) { arrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat()) }
    }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (painted != null) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to painted.top,
                        0.55f to painted.mid,
                        1f to painted.bottom,
                    ),
                )
            } else {
                drawRect(bg)
            }

            val w = size.width
            val h = size.height
            val d = phase.toDouble()

            // Horizon: the glow pooling at the bottom like low evening light.
            drawRect(
                brush = Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    1f to dynamic.copy(alpha = if (dark) 0.16f else 0.10f),
                ),
            )

            // Primary light-pool — the warm accent drifting across the top.
            val cx1 = w * (0.5f + 0.30f * sin(d).toFloat())
            val cy1 = h * (0.17f + 0.05f * cos(d).toFloat())
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(dynamic.copy(alpha = (if (dark) 0.22f else 0.13f) * breath), Color.Transparent),
                    center = Offset(cx1, cy1), radius = w * 0.95f,
                ),
                radius = w * 0.95f, center = Offset(cx1, cy1),
            )

            // Secondary cooler pool, slower and offset — gives the sky depth.
            val cx2 = w * (0.5f - 0.34f * sin(d * 0.6 + 1.7).toFloat())
            val cy2 = h * (0.34f + 0.10f * sin(d * 0.5).toFloat())
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(glow2.copy(alpha = (if (dark) 0.15f else 0.08f) * breath), Color.Transparent),
                    center = Offset(cx2, cy2), radius = w * 0.80f,
                ),
                radius = w * 0.80f, center = Offset(cx2, cy2),
            )

            // Aurora ribbon: a faint band of accent light that sweeps diagonally
            // across the sky, like a borealis ribbon. Gated by the motion level.
            if (moving) {
                val sweep = ((d.toFloat() / (2f * Math.PI.toFloat())) + 1f) % 1f
                val bandX = (-0.4f + 1.2f * sweep) * w
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            dynamic.copy(alpha = (if (dark) 0.12f else 0.07f) * breath),
                            Color.Transparent,
                        ),
                        start = Offset(bandX - 0.22f * w, 0f),
                        end = Offset(bandX + 0.22f * w, h),
                    ),
                )
            }

            // Rising motes: faint specks that drift slowly upward and wrap,
            // giving the surface a sense of live atmosphere. Static at level 0.
            val moteAlpha = if (dark) 0.08f else 0.05f
            motes.forEach { m ->
                val mx = (m[0] + 0.02f * sin(d * m[3] + m[1] * 6f).toFloat()) * w
                val my = if (moving) ((m[1] - phase * 0.02f * m[3]) % 1f + 1f) % 1f * h else m[1] * h
                drawCircle(
                    color = glow.copy(alpha = moteAlpha * (0.4f + m[2])),
                    radius = 1.1f + m[2] * 2.2f,
                    center = Offset(mx, my),
                )
            }

            // Vignette, so the corners fall away and the content sits forward.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.55f else 0.06f)),
                    center = Offset(w / 2, h * 0.42f),
                    radius = w * 1.15f,
                ),
            )

            // Grain.
            val alpha = if (dark) 0.05f else 0.035f
            grain.forEach { (fx, fy, fs) ->
                drawCircle(
                    color = Color.White.copy(alpha = alpha * (0.4f + fs)),
                    radius = 0.6f + fs * 0.9f,
                    center = Offset(fx * w, fy * h),
                )
            }
        }
        content()
    }
}

/** Frosted panel: translucent surface, hairline edge, soft corner, reflective sheen. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
    corner: Dp = 22.dp,
    glow: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    val moving = LocalAnimLevel.current > 0
    // A slow sheen drifts once across the glass, like light moving over a pane.
    val phase = if (moving) {
        rememberInfiniteTransition(label = "sheen").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(10_000, easing = LinearEasing), RepeatMode.Restart,
            ),
            label = "phase",
        ).value
    } else -1f

    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(corner),
            color = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.62f else 0.86f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.10f else 0.34f)),
            modifier = Modifier.fillMaxSize(),
        ) { content() }
        // Reflective sheen drawn over the glass (subtle, gated by motion).
        Canvas(Modifier.matchParentSize()) {
            if (phase >= 0f) {
                val sweep = phase * (size.width + 300f) - 160f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            glow.copy(alpha = 0.09f),
                            Color.Transparent,
                        ),
                        start = Offset(sweep - 120f, 0f),
                        end = Offset(sweep + 120f, size.height),
                    ),
                    size = size,
                )
            }
        }
    }
}
