package com.axzora.os

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.happyreddy.MrHappyService

/**
 * The single place to see and control the Mr Happy accessibility service:
 * whether it's enabled, what it's allowed to do (Safe vs Full control), and a
 * launch button to turn it on — because every "scrub the screen / tap / type /
 * auto-send" action depends on it.
 */
@Composable
fun AccessibilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary

    var enabled by remember { mutableStateOf(serviceEnabled(context)) }
    var restricted by remember { mutableStateOf(MrHappyService.isRestricted(context)) }

    val controls = listOf(
        "Tap, long-press & swipe anywhere",
        "Type into any text box",
        "Press Home / Back / Recents / Notifications",
        "Open Quick Settings & power menu",
        "Read the screen (what's shown) aloud",
        "Auto-send WhatsApp & messages",
        "Scroll to & tap an item",
        "Take a screenshot, lock the phone",
    )

    Column(
        Modifier.fillMaxSize().padding(top = 44.dp).verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Accessibility", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
        }

        Column(Modifier.padding(20.dp)) {

            // ---- service status ----
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Accessibility, null,
                            tint = if (enabled) Color(0xFF22C55E) else accent,
                            modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (enabled) "Mr Happy control is ON" else "Mr Happy control is OFF",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Mr Happy controls the phone through Android's Accessibility service. " +
                            "Tap, type, read the screen, and auto-send messages all need it. " +
                            "Nothing is downloaded — it runs entirely on your phone.",
                        fontSize = 12.sp, lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!enabled) {
                        Button(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Enable Mr Happy") }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Find the app, choose it, and flip the toggle on. Come back here " +
                                "and it will show ON.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        )
                    } else {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Open accessibility settings") }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Refresh the status in case they enabled it and came back.
                    TextButton(onClick = {
                        enabled = serviceEnabled(context)
                        restricted = MrHappyService.isRestricted(context)
                    }) { Text("Check status") }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- control level ----
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Control level", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Full control", fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onBackground)
                            Text(
                                if (!restricted) "Mr Happy can use every tool, including the sensitive ones." else
                                    "Limited to safe tools. Sensitive actions are blocked.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                        Switch(
                            checked = !restricted,
                            onCheckedChange = { full ->
                                restricted = !full
                                MrHappyService.setRestricted(context, !full)
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Restricted mode keeps send/format/invasive actions away from the " +
                            "model until you choose to allow them.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- what it controls ----
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("What it can control", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(10.dp))
                    controls.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 5.dp)) {
                            Icon(Icons.Rounded.CheckCircle, null,
                                tint = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(c, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.85f else 0.5f))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (enabled) "All of these are live right now." else
                            "Enable the service to switch these on.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/** True when the Mr Happy accessibility service is enabled by the user. */
private fun serviceEnabled(context: Context): Boolean = runCatching {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val enabled = am?.getEnabledAccessibilityServiceList(
        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
    ) ?: return false
    enabled.any { svc ->
        svc.resolveInfo.serviceInfo.packageName == "com.axzora.os" &&
            svc.resolveInfo.serviceInfo.name == "com.axzora.happyreddy.HappyReddyA11y"
    }
}.getOrDefault(false)
