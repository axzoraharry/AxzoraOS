package com.axzora.os

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.devices.Device
import com.axzora.os.devices.DeviceHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every Axzora device this account owns, live.
 *
 * Refreshes on a short timer so a phone coming online, or its battery
 * changing, shows up here within seconds without the user doing anything.
 * "Ring" is the find-my-phone action — the target device sounds off, which is
 * only ever your own hardware because the server checks ownership.
 */
@Composable
fun DevicesScreen(
    hub: DeviceHub,
    uid: String,
    accent: Color,
    onBack: () -> Unit,
) {
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var ringing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uid) {
        while (true) {
            devices = hub.list(uid)
            loaded = true
            delay(4000)
        }
    }

    val online = devices.count { it.online }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // A soft glow behind the header, so the screen has depth rather than a
        // flat fill.
        Box(
            Modifier.fillMaxWidth().height(320.dp).background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                ),
            ),
        )

        Column(Modifier.fillMaxSize().padding(top = 46.dp)) {
            TopRowDevices(onBack)

            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(
                    "My Devices",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !loaded -> "Looking for your devices…"
                        devices.isEmpty() -> "No devices yet"
                        online == devices.size -> "All $online online"
                        else -> "$online of ${devices.size} online"
                    },
                    fontSize = 14.sp,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(18.dp))

            if (loaded && devices.isEmpty()) {
                EmptyDevices(accent)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(devices, key = { _, d -> d.deviceId }) { i, d ->
                        DeviceCard(
                            device = d,
                            index = i,
                            accent = accent,
                            isSelf = d.deviceId == hub.deviceId,
                            ringing = ringing == d.deviceId,
                            onRing = {
                                ringing = d.deviceId
                                scope.launch {
                                    hub.ring(uid, d.deviceId)
                                    delay(3000)
                                    if (ringing == d.deviceId) ringing = null
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    index: Int,
    accent: Color,
    isSelf: Boolean,
    ringing: Boolean,
    onRing: () -> Unit,
) {
    // Cards arrive as a wave — a small, high-impact reveal.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(index * 70L); shown = true }
    val enter by animateFloatAsState(if (shown) 1f else 0f, tween(360), label = "enter")

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (device.online) accent.copy(alpha = 0.28f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            alpha = enter
            translationY = (1f - enter) * 30f
        },
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Battery ring wrapping a phone glyph — the at-a-glance health of
            // the device.
            BatteryRing(
                percent = device.battery,
                charging = device.charging,
                online = device.online,
                accent = accent,
            )

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name.ifBlank { device.model.ifBlank { "My device" } },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isSelf) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = accent.copy(alpha = 0.18f),
                        ) {
                            Text(
                                "This device",
                                fontSize = 10.sp,
                                color = accent,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    device.model.ifBlank { "Android" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(online = device.online, accent = accent)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (device.online) {
                            if (device.charging) "Online · charging" else "Online"
                        } else {
                            "Last seen ${ago(device.seen)}"
                        },
                        fontSize = 12.sp,
                        color = if (device.online) accent
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }

            // Ringing your other phone: only offered for a device that isn't
            // this one and is online to hear it.
            if (!isSelf && device.online) {
                Spacer(Modifier.width(8.dp))
                RingButton(ringing = ringing, accent = accent, onRing = onRing)
            }
        }
    }
}

/** A battery arc that fills clockwise, red near empty, with a charging bolt. */
@Composable
private fun BatteryRing(percent: Int, charging: Boolean, online: Boolean, accent: Color) {
    val target = (percent.coerceIn(0, 100)) / 100f
    val fill by animateFloatAsState(if (percent < 0) 0f else target, tween(700), label = "batt")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val ringColor = when {
        !online -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        percent in 0..15 -> Color(0xFFFF6B6B)
        percent in 16..35 -> Color(0xFFF5A524)
        else -> accent
    }

    Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fill > 0f) {
                drawArc(
                    color = ringColor, startAngle = -90f, sweepAngle = 360f * fill,
                    useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        if (charging) {
            Icon(
                Icons.Rounded.Bolt, "Charging",
                tint = ringColor, modifier = Modifier.size(22.dp),
            )
        } else if (percent >= 0) {
            Text(
                "$percent",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Icon(
                Icons.Rounded.PhoneAndroid, null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A green dot that breathes while the device is online. */
@Composable
private fun LiveDot(online: Boolean, accent: Color) {
    if (!online) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
        )
        return
    }
    val t = rememberInfiniteTransition(label = "dot")
    val pulse by t.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(12.dp).graphicsLayer { alpha = (1f - pulse) * 0.6f }
                .clip(CircleShape).background(Color(0xFF22C55E)),
        )
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E)),
        )
    }
}

@Composable
private fun RingButton(ringing: Boolean, accent: Color, onRing: () -> Unit) {
    val t = rememberInfiniteTransition(label = "ring")
    val wobble by t.animateFloat(
        -12f, 12f,
        infiniteRepeatable(tween(180, easing = LinearEasing), RepeatMode.Reverse),
        label = "wobble",
    )
    Surface(
        shape = CircleShape,
        color = if (ringing) accent else accent.copy(alpha = 0.16f),
        modifier = Modifier.size(46.dp).clickable(enabled = !ringing) { onRing() },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.NotificationsActive,
                "Ring this device",
                tint = if (ringing) Color(0xFF221606) else accent,
                modifier = Modifier.size(22.dp).graphicsLayer {
                    if (ringing) rotationZ = wobble
                },
            )
        }
    }
}

@Composable
private fun EmptyDevices(accent: Color) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(
            Icons.Rounded.PhoneAndroid, null,
            tint = accent.copy(alpha = 0.6f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "This is your only Axzora device so far.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Install Axzora on another phone or tablet with the same account " +
                "and it will appear here — online status, battery, and a button " +
                "to ring it when you've misplaced it.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun TopRowDevices(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.ArrowBackIosNew,
            "Back",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onBack() }
                .padding(2.dp),
        )
    }
}

private fun ago(ts: Long): String {
    if (ts <= 0) return "a while ago"
    val secs = (System.currentTimeMillis() - ts) / 1000
    return when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60} min ago"
        secs < 86400 -> "${secs / 3600} hr ago"
        else -> "${secs / 86400} d ago"
    }
}
