package com.axzora.os.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One place that decides how big things are, so the same layout works on a
 * 4-inch phone, a 6.7-inch flagship, a foldable and a tablet.
 *
 * Android phones range from about 320dp to 480dp wide, and tablets go past
 * 600dp. A layout built from fixed dp values looks cramped at the bottom of
 * that range and marooned at the top — so sizes here are derived from the
 * width the app actually has, not guessed once and hard-coded.
 */
data class Dimens(
    /** Narrow phones, where every spare pixel matters. */
    val compact: Boolean,
    /** Wide enough to lay out like a tablet. */
    val expanded: Boolean,
    /** Screen edge padding. */
    val gutter: Dp,
    /** Vertical rhythm between sections. */
    val gap: Dp,
    val appIcon: Dp,
    val gridColumns: Int,
    /** Height of the "Ask Axzora" bar. */
    val barHeight: Dp,
    val dockIcon: Dp,
    val corner: Dp,
    /** Widest a chat bubble may get, so text doesn't run edge to edge. */
    val bubbleMax: Dp,
    /** Multiplier for display type — the clock and headings. */
    val displayScale: Float,
)

@Composable
fun rememberDimens(): Dimens {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp
    val h = cfg.screenHeightDp

    return remember(w, h) {
        val compact = w < 360
        val expanded = w >= 600
        // Short screens (landscape, small phones) get tighter vertical rhythm
        // so the dock never gets pushed off the bottom.
        val short = h < 640

        Dimens(
            compact = compact,
            expanded = expanded,
            gutter = when {
                compact -> 14.dp
                expanded -> 32.dp
                else -> 20.dp
            },
            gap = when {
                short -> 10.dp
                expanded -> 24.dp
                else -> 16.dp
            },
            appIcon = when {
                compact -> 46.dp
                expanded -> 64.dp
                else -> 54.dp
            },
            // Keep tiles a comfortable size rather than stretching four of
            // them across a tablet.
            gridColumns = when {
                w >= 840 -> 7
                w >= 600 -> 6
                w >= 480 -> 5
                compact -> 4
                else -> 4
            },
            barHeight = when {
                compact -> 54.dp
                expanded -> 70.dp
                else -> 62.dp
            },
            dockIcon = if (compact) 20.dp else 24.dp,
            corner = if (expanded) 34.dp else 28.dp,
            // A fraction of the width, capped, so bubbles stay readable on a
            // tablet instead of stretching into long unbroken lines.
            bubbleMax = minOf(w * 0.78f, 460f).dp,
            displayScale = when {
                compact -> 0.88f
                expanded -> 1.15f
                else -> 1f
            },
        )
    }
}
