package com.axzora.os.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Accent choices, all warm or jewel-toned.
 *
 * The palette leans away from the cold blue that every other launcher uses:
 * Axzora holds money and family, and a warm identity suits that better than
 * a tech-blue one. Each accent is picked to stay legible on the near-black
 * background at small sizes.
 */
val AccentPresets = listOf(
    "Ember" to Color(0xFFF0A73C),
    "Rose" to Color(0xFFE8657F),
    "Jade" to Color(0xFF37C79A),
    "Cobalt" to Color(0xFF5B8DEF),
    "Orchid" to Color(0xFFB07CE8),
    "Copper" to Color(0xFFD97A5A),
    "Aqua" to Color(0xFF4FD8D0),
    "Gold" to Color(0xFFE8C15A),
)

/**
 * A full background treatment, not just a colour: every surface tier moves
 * together so cards, outlines and text keep their contrast relationships in
 * each style.
 */
data class ThemeStyle(
    val name: String,
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
)

/**
 * The looks the user can pick from.
 *
 * Ember is the house style — warm near-black with a hint of brown in the
 * shadows so amber accents don't float. Pitch is true #000000 for OLED
 * screens, where a black pixel is simply off and costs no battery. Midnight
 * and Forest tint the shadows cool and green. Paper is the light style.
 */
val ThemeStyles = listOf(
    ThemeStyle("Ember", true, Color(0xFF0C0A09), Color(0xFF1A1715), Color(0xFF262220), Color(0xFF3A3532)),
    ThemeStyle("Pitch", true, Color(0xFF000000), Color(0xFF141412), Color(0xFF1E1E1C), Color(0xFF32302E)),
    ThemeStyle("Midnight", true, Color(0xFF0A0E1A), Color(0xFF161C2E), Color(0xFF1F2740), Color(0xFF34405E)),
    ThemeStyle("Forest", true, Color(0xFF0A120D), Color(0xFF16221B), Color(0xFF1E2E25), Color(0xFF32493C)),
    ThemeStyle("Paper", false, Color(0xFFFBF8F4), Color(0xFFFFFFFF), Color(0xFFF0EBE3), Color(0xFFD9D2C9)),
    ThemeStyle("Nebula", true, Color(0xFF0E0A1A), Color(0xFF1C1530), Color(0xFF291F45), Color(0xFF3F2F67)),
    ThemeStyle("Crimson", true, Color(0xFF120A0C), Color(0xFF231014), Color(0xFF331820), Color(0xFF55242F)),
    ThemeStyle("Slate", true, Color(0xFF0B0E12), Color(0xFF171B21), Color(0xFF232A34), Color(0xFF39434F)),
    ThemeStyle("Sand", false, Color(0xFFF7F2E8), Color(0xFFFFFFFF), Color(0xFFEDE4D2), Color(0xFFD8C9AE)),
)

/**
 * How much motion the UI is allowed: 0 none, 1 essential only, 2 everything.
 *
 * A composition local rather than a parameter, because motion is a
 * cross-cutting choice — threading it through every screen signature would
 * touch half the app for what is really one global dial.
 */
val LocalAnimLevel = compositionLocalOf { 2 }

/**
 * Multiplier applied to every animation duration. 1.0 is the designed pace;
 * smaller is snappier. One dial, so "make it faster" is a setting rather
 * than a hunt through every screen.
 */
val LocalAnimSpeed = compositionLocalOf { 1f }

/** A duration honouring the user's speed dial. */
@Composable
fun animMs(base: Int): Int = (base * LocalAnimSpeed.current).toInt().coerceAtLeast(1)

private val Ink = Color(0xFF0C0A09)
private val InkOn = Color(0xFFF7F5F2)
private val PaperOn = Color(0xFF1A1715)

@Composable
fun AxzoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color = AccentPresets.first().second,
    style: ThemeStyle? = null,
    content: @Composable () -> Unit,
) {
    // Callers that predate styles (the floating bubble, for one) still pass
    // only darkTheme; they get the matching house style.
    val s = style ?: if (darkTheme) ThemeStyles[0] else ThemeStyles.last()

    val colors = if (s.dark) {
        darkColorScheme(
            primary = accent,
            // Text on an amber button must be dark; white on amber fails
            // contrast badly at small label sizes.
            onPrimary = Ink,
            secondary = accent,
            background = s.background,
            onBackground = InkOn,
            surface = s.surface,
            onSurface = InkOn,
            surfaceVariant = s.surfaceVariant,
            outline = s.outline,
            error = Color(0xFFFF6B6B),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent,
            background = s.background,
            onBackground = PaperOn,
            surface = s.surface,
            onSurface = PaperOn,
            surfaceVariant = s.surfaceVariant,
            outline = s.outline,
        )
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AxzoraType,
        content = content,
    )
}
