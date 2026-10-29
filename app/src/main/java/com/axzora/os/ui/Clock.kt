package com.axzora.os.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The home screen's face: time, greeting and date in one of four voices.
 *
 * Serif is the house look — warm, editorial. Stack is the poster look,
 * hours over minutes. Thin is a whisper of a clock for people who want the
 * wallpaper to win. Quiet flips the hierarchy: the day large, the time
 * small, for people who reach for their phone to know *what day it is*
 * more than the minute.
 *
 * One composable serves the real home screen and the style previews, so a
 * preview can never drift from the truth.
 */
@Composable
fun ClockBlock(
    style: Int,
    size: Int,
    time: String,
    date: String,
    meridiem: String,
    greetingLine: String,
    accent: Color,
    onBg: Color,
    scale: Float = 1f,
) {
    val f = clockScale(size) * scale

    // Ambient halo: the clock sits in a soft accent glow that breathes, so it
    // reads as lit rather than just printed. Gated by the Animations setting.
    val moving = LocalAnimLevel.current > 0
    val breathe = if (moving) {
        rememberInfiniteTransition(label = "clock").animateFloat(
            initialValue = 0.6f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(3600, easing = LinearEasing), RepeatMode.Reverse,
            ),
            label = "breathe",
        ).value
    } else -1f

    Box {
        if (breathe >= 0f) {
            Canvas(Modifier.matchParentSize()) {
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val r = this.size.maxDimension
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.10f * breathe), Color.Transparent),
                        center = c, radius = r,
                    ),
                    radius = r, center = c,
                )
            }
        }

    when (style) {
        ClockStyles.STACK -> {
            val parts = time.split(":")
            val hours = parts.getOrElse(0) { time }
            val minutes = parts.getOrElse(1) { "" }
            Column {
                Text(
                    hours,
                    fontSize = (66 * f).sp,
                    lineHeight = (60 * f).sp,
                    fontWeight = FontWeight.Black,
                    color = onBg,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        minutes,
                        fontSize = (66 * f).sp,
                        lineHeight = (60 * f).sp,
                        fontWeight = FontWeight.Black,
                        color = accent,
                    )
                    Spacer(Modifier.width((8 * f).dp))
                    Text(
                        meridiem,
                        fontSize = (13 * f).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onBg.copy(alpha = 0.55f),
                    )
                }
                Spacer(Modifier.height((8 * f).dp))
                Text(
                    greetingLine,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = (22 * f).sp),
                    color = onBg.copy(alpha = 0.94f),
                )
                Text(
                    date.uppercase(),
                    fontSize = (11 * f).sp,
                    letterSpacing = 2.sp,
                    color = onBg.copy(alpha = 0.42f),
                )
            }
        }

        ClockStyles.THIN -> {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        time,
                        fontSize = (84 * f).sp,
                        lineHeight = (84 * f).sp,
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = (-2).sp,
                        color = onBg,
                    )
                    Spacer(Modifier.width((10 * f).dp))
                    Text(
                        meridiem,
                        fontSize = (13 * f).sp,
                        color = accent,
                        modifier = Modifier.padding(bottom = (14 * f).dp),
                    )
                }
                Spacer(Modifier.height((2 * f).dp))
                Text(
                    greetingLine,
                    fontSize = (17 * f).sp,
                    fontWeight = FontWeight.Light,
                    color = onBg.copy(alpha = 0.9f),
                )
                Text(
                    date.uppercase(),
                    fontSize = (11 * f).sp,
                    letterSpacing = 3.sp,
                    color = onBg.copy(alpha = 0.4f),
                )
            }
        }

        ClockStyles.QUIET -> {
            Column {
                Text(
                    date,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = (34 * f).sp,
                        lineHeight = (38 * f).sp,
                        fontStyle = FontStyle.Italic,
                    ),
                    color = onBg,
                )
                Spacer(Modifier.height((6 * f).dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        time,
                        fontSize = (30 * f).sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                    )
                    Spacer(Modifier.width((6 * f).dp))
                    Text(
                        meridiem,
                        fontSize = (12 * f).sp,
                        color = onBg.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = (4 * f).dp),
                    )
                }
                Spacer(Modifier.height((4 * f).dp))
                Text(
                    greetingLine,
                    fontSize = (15 * f).sp,
                    color = onBg.copy(alpha = 0.75f),
                )
            }
        }

        else -> { // SERIF, the house style
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        time,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = (72 * f).sp,
                            brush = Brush.linearGradient(
                                listOf(onBg, onBg.copy(alpha = 0.78f), accent),
                            ),
                        ),
                    )
                    Spacer(Modifier.width((8 * f).dp))
                    Text(
                        meridiem,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(bottom = (18 * f).dp),
                    )
                }
                Text(
                    greetingLine,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = (24 * f).sp),
                    color = onBg.copy(alpha = 0.94f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    date.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = onBg.copy(alpha = 0.42f),
                )
            }
        }
    }
    }
}
