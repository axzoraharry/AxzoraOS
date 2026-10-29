package com.axzora.os.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * A painted sky for the phone to live under: three stops of a vertical
 * gradient plus the colour of the low glow. One definition drives three
 * renderers — the live home background, the preview swatches, and the real
 * wallpaper bitmap — so what you pick is exactly what you get.
 */
data class Atmosphere(
    val name: String,
    val top: Color,
    val mid: Color,
    val bottom: Color,
    val glow: Color,
)

/**
 * Index 0 is "your wallpaper": Axzora draws only its usual glow over
 * whatever the phone already shows. The painted ones replace it entirely.
 */
val Atmospheres = listOf(
    Atmosphere("Wallpaper", Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified),
    Atmosphere(
        "Ember dusk",
        Color(0xFF0C0A09), Color(0xFF1C1108), Color(0xFF3A2008),
        Color(0xFFF0A73C),
    ),
    Atmosphere(
        "Deep sea",
        Color(0xFF050B14), Color(0xFF0A1A2E), Color(0xFF0E2A44),
        Color(0xFF5B8DEF),
    ),
    Atmosphere(
        "Old forest",
        Color(0xFF07110B), Color(0xFF0D2114), Color(0xFF14331E),
        Color(0xFF37C79A),
    ),
    Atmosphere(
        "First light",
        Color(0xFF1A0E14), Color(0xFF3A1A22), Color(0xFF7A3A30),
        Color(0xFFE8657F),
    ),
)

/** How the home clock is drawn. Kept as plain ints so DataStore stays simple. */
object ClockStyles {
    const val SERIF = 0
    const val STACK = 1
    const val THIN = 2
    const val QUIET = 3
    val names = listOf("Serif", "Stack", "Thin", "Quiet")
}

/** Multiplier for each clock size step. */
fun clockScale(size: Int): Float = when (size.coerceIn(0, 2)) {
    0 -> 0.82f
    2 -> 1.2f
    else -> 1f
}

/**
 * Draws an atmosphere as a real image, at the phone's own resolution.
 *
 * The same recipe as the live background — gradient, low glow, vignette,
 * grain — because a lock screen that almost matches the home screen reads
 * as a mistake rather than a set.
 */
private fun renderAtmosphere(atm: Atmosphere, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.shader = LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        intArrayOf(atm.top.toArgb(), atm.mid.toArgb(), atm.bottom.toArgb()),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    // The glow pooling low, like the home screen's horizon.
    paint.shader = RadialGradient(
        w * 0.5f, h * 0.92f, w * 1.1f,
        intArrayOf(
            (atm.glow.copy(alpha = 0.30f)).toArgb(),
            android.graphics.Color.TRANSPARENT,
        ),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP,
    )
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    // Vignette keeps the top calm so the clock and notifications sit well.
    paint.shader = RadialGradient(
        w * 0.5f, h * 0.42f, h * 0.75f,
        intArrayOf(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.argb(110, 0, 0, 0),
        ),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP,
    )
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

    // Grain, so OLED panels don't band across the big gradient.
    paint.shader = null
    val rnd = Random(7)
    repeat(900) {
        val fx = rnd.nextFloat() * w
        val fy = rnd.nextFloat() * h
        val fs = rnd.nextFloat()
        paint.color = android.graphics.Color.argb((14 * (0.4f + fs)).toInt(), 255, 255, 255)
        c.drawCircle(fx, fy, 1f + fs * 1.6f, paint)
    }
    return bmp
}

/**
 * Applies a painted atmosphere as the phone's real wallpaper.
 *
 * This is the part that themes the actual Android lock screen: the flags ask
 * the system to use the image on the lock screen, the home wallpaper, or
 * both. Returns false when the launcher isn't allowed to change wallpaper
 * (some work profiles forbid it) rather than crashing the settings screen.
 */
suspend fun applyAtmosphereWallpaper(
    context: Context,
    atm: Atmosphere,
    lock: Boolean,
    home: Boolean,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val dm = context.resources.displayMetrics
        val bmp = renderAtmosphere(atm, dm.widthPixels, dm.heightPixels)
        val wm = WallpaperManager.getInstance(context)
        var flags = 0
        if (lock) flags = flags or WallpaperManager.FLAG_LOCK
        if (home) flags = flags or WallpaperManager.FLAG_SYSTEM
        if (flags == 0) return@runCatching false
        wm.setBitmap(bmp, null, true, flags)
        true
    }.getOrDefault(false)
}
