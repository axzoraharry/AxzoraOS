package com.axzora.os

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.axzora.os.ui.AuroraBackground

/**
 * First-run flow: brand hello -> your name -> family circle (code + server).
 *
 * Finishing with a family code counts as joining that circle: everyone in it
 * sees each other on the map, which the page states in plain words before the
 * user continues. Location permission is requested here; sharing starts only
 * when the user taps "Join circle & start sharing".
 */
@Composable
fun OnboardingScreen(
    accent: Color,
    onFinish: (name: String, familyCode: String, serverUrl: String, shareLocation: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // Users never type a server: the app ships pointed at the Axzora backend.
    // Power users can still change it in Settings -> Advanced.
    val server = Config.DEFAULT_SERVER

    var locGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        locGranted = r[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    AuroraBackground(accent = accent, dark = true) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(70.dp))

            // page dots
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(if (i == page) 22.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(if (i == page) accent else Color.White.copy(alpha = 0.25f))
                    )
                }
            }

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (slideInHorizontally { it / 3 } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut())
                },
                label = "onboard",
                modifier = Modifier.weight(1f),
            ) { p ->
                when (p) {
                    0 -> OnboardPage(
                        icon = Icons.Rounded.AutoAwesome,
                        accent = accent,
                        title = "Welcome to Axzora OS",
                        body = "Your home screen, assistant, payments and private family circle — in one place. Voice and chat work fully offline.",
                    )
                    1 -> OnboardPage(
                        icon = Icons.Rounded.AutoAwesome,
                        accent = accent,
                        title = "What should we call you?",
                        body = "Your name appears on the home screen, in family chat, and on the family map.",
                    ) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Your name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    2 -> {
                        // Rendered full-bleed: it has its own heading and
                        // action, so the shared page frame would fight it.
                        PermissionsPage(accent = accent) { page = 3 }
                    }
                    else -> OnboardPage(
                        icon = Icons.Rounded.Groups,
                        accent = accent,
                        title = "Join your family circle",
                        body = "Everyone who installs Axzora and enters the SAME circle code sees each other on the map and can chat privately — encrypted end to end.",
                    ) {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it.trim() },
                            label = { Text("Circle code (share it with family)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (locGranted) Icons.Rounded.LocationOn else Icons.Rounded.Lock,
                                null, tint = accent, modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (locGranted) "Location permission granted"
                                else "Location permission needed to appear on the family map",
                                fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                        if (!locGranted) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                permLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                            }) { Text("Allow location") }
                        }
                    }
                }
            }

            // actions
            // The permissions page owns its own buttons.
            if (page == 2) {
                Spacer(Modifier.height(26.dp))
                return@Column
            }

            Button(
                onClick = {
                    when (page) {
                        0 -> page = 1
                        1 -> page = 2
                        else -> onFinish(name, code, server, code.isNotBlank() && locGranted)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    when (page) {
                        0 -> "Get started"
                        1 -> "Continue"
                        else -> if (code.isNotBlank() && locGranted)
                            "Join circle & start sharing" else "Finish"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(
                onClick = { onFinish(name, code, server, false) },
                modifier = Modifier.padding(top = 4.dp, bottom = 26.dp),
            ) {
                Text("Skip for now", color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun OnboardPage(
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.08f)))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(
            title, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body, fontSize = 15.sp, lineHeight = 22.sp,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        if (extra != null) {
            Spacer(Modifier.height(24.dp))
            extra()
        }
    }
}
