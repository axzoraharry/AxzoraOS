package com.axzora.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.axzora.os.assistant.FloatingService

/** One capability, described in terms of what it lets the user do. */
private data class Grant(
    val icon: ImageVector,
    val title: String,
    val why: String,
    val permissions: List<String>,
)

/**
 * Counts what Axzora still needs, so Settings can say so plainly rather than
 * leaving the user to discover a dead feature.
 */
fun missingPermissions(context: android.content.Context): Int {
    val runtime = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    val denied = runtime.count {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    return denied + if (FloatingService.canDrawOverlays(context)) 0 else 1
}

/**
 * Asks for everything Axzora needs in one place, during setup.
 *
 * Requesting permissions lazily meant a feature would stop halfway to raise a
 * system dialog — the phone would ring with no sound because notifications had
 * never been allowed, or Nearby would quietly find nobody. Asking once, with
 * the reason next to each item, avoids the ambush and makes the trade explicit.
 */
@Composable
fun PermissionsPage(
    accent: Color,
    onDone: () -> Unit,
) {
    val context = LocalContext.current

    val grants = remember {
        buildList {
            add(
                Grant(
                    Icons.Rounded.Notifications, "Notifications",
                    "Ring for calls and alert you to messages and payments",
                    if (Build.VERSION.SDK_INT >= 33)
                        listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
                )
            )
            add(
                Grant(
                    Icons.Rounded.Mic, "Microphone",
                    "Voice calls and the offline assistant",
                    listOf(Manifest.permission.RECORD_AUDIO),
                )
            )
            add(
                Grant(
                    Icons.Rounded.Videocam, "Camera",
                    "Video calls",
                    listOf(Manifest.permission.CAMERA),
                )
            )
            add(
                Grant(
                    Icons.Rounded.LocationOn, "Location",
                    "Show you on your family's map, and find phones nearby",
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            )
            if (Build.VERSION.SDK_INT >= 31) {
                add(
                    Grant(
                        Icons.Rounded.Bluetooth, "Nearby devices",
                        "Share files and money offline, with no internet",
                        listOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ),
                    )
                )
            }
        }
    }

    fun granted(g: Grant) = g.permissions.isEmpty() || g.permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var state by remember { mutableStateOf(grants.map { granted(it) }) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        state = grants.map { granted(it) }
        asked = true
    }

    val allGranted = state.all { it }

    // Re-checked on every resume, since the user grants this on a different
    // screen and comes back.
    var overlayOk by remember { mutableStateOf(FloatingService.canDrawOverlays(context)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayOk = FloatingService.canDrawOverlays(context)
                state = grants.map { granted(it) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))

        Text(
            "A few permissions",
            fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Axzora asks for these once, up front, so nothing stops working halfway. " +
                "You can refuse any of them and the rest still works.",
            fontSize = 14.sp, lineHeight = 21.sp,
            color = Color.White.copy(alpha = 0.65f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        grants.forEachIndexed { i, g ->
            val ok = state.getOrElse(i) { false }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        .background(
                            if (ok) accent.copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.07f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        g.icon, null, modifier = Modifier.size(20.dp),
                        tint = if (ok) accent else Color.White.copy(alpha = 0.55f),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        g.why, fontSize = 12.sp, lineHeight = 16.sp,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
                if (ok) {
                    Icon(
                        Icons.Rounded.CheckCircle, "Allowed",
                        tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Special access — Android will not grant this from a runtime dialog,
        // so it gets its own row and its own trip to system settings.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(
                        if (overlayOk) accent.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.07f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(20.dp),
                    tint = if (overlayOk) accent else Color.White.copy(alpha = 0.55f),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Floating bubble", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    "Show the Axzora button over other apps",
                    fontSize = 12.sp, lineHeight = 16.sp,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            if (overlayOk) {
                Icon(
                    Icons.Rounded.CheckCircle, "Allowed",
                    tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp),
                )
            } else {
                TextButton(onClick = { FloatingService.requestPermission(context) }) {
                    Text("Allow", color = accent)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (allGranted) onDone()
                else launcher.launch(grants.flatMap { it.permissions }.toTypedArray())
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                if (allGranted) "Continue" else "Allow all",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        // Only offer to move on without them once the choice has actually been
        // made, so the button isn't an easy way to skip the explanation.
        if (asked && !allGranted) {
            TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) {
                Text("Continue without", color = Color.White.copy(alpha = 0.5f))
            }
        }
        Spacer(Modifier.height(26.dp))
    }
}
