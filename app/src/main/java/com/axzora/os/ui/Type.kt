package com.axzora.os.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.axzora.os.R

/**
 * Two typefaces, chosen so Axzora doesn't read as stock Android.
 *
 * Both are variable fonts, so every weight is cut from a single file rather
 * than shipping eight statics — the whole type system costs about 460 KB.
 *
 * - [Display] Fraunces: a soft, slightly quirky serif. Used for the clock,
 *   balances and screen titles, where a little character is worth having.
 * - [Body] Outfit: a geometric sans with a tall x-height that stays legible
 *   at 11–13sp, which is where most of this interface actually lives.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Display = FontFamily(
    variable(R.font.fraunces, FontWeight.Normal),
    variable(R.font.fraunces, FontWeight.Medium),
    variable(R.font.fraunces, FontWeight.SemiBold),
    variable(R.font.fraunces, FontWeight.Bold),
    variable(R.font.fraunces, FontWeight.Light),
)

val Body = FontFamily(
    variable(R.font.outfit, FontWeight.Light),
    variable(R.font.outfit, FontWeight.Normal),
    variable(R.font.outfit, FontWeight.Medium),
    variable(R.font.outfit, FontWeight.SemiBold),
    variable(R.font.outfit, FontWeight.Bold),
)

/**
 * Tight tracking on the large sizes and slightly open tracking on the small
 * ones — big type looks loose at default spacing, small type looks cramped.
 */
val AxzoraType = Typography(
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Light,
        fontSize = 64.sp, lineHeight = 66.sp, letterSpacing = (-2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, letterSpacing = 0.3.sp,
    ),
    /** Section headers: small, wide-tracked, uppercase at the call site. */
    labelSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 1.4.sp,
    ),
)
