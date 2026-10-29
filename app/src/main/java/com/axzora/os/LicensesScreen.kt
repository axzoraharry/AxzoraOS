package com.axzora.os

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** In-app "Open Source Licenses" page — mirrors app/NOTICE.md. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 46.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Open Source Licenses", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
        }

        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text(
                "Axzora OS is built on open-source software. Licenses are listed per " +
                    "component; full text is in app/NOTICE.md.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(12.dp))

            LicenseSection("Dependencies")
            LicenseRow("androidx (core, lifecycle, activity, navigation, datastore)", "Apache 2.0")
            LicenseRow("Jetpack Compose (ui, material3, material-icons-extended)", "Apache 2.0")
            LicenseRow("Google Play Services (location, nearby)", "Apache 2.0")
            LicenseRow("Google MediaPipe tasks-genai", "Apache 2.0")
            LicenseRow("stream-webrtc-android (WebRTC)", "BSD 3-Clause")
            LicenseRow("osmdroid", "Apache 2.0")

            LicenseSection("Bundled native / JAR")
            LicenseRow("Vosk (speech model, libvosk.so)", "Apache 2.0 · © Alpha Cephei 2020")
            LicenseRow("Picovoice Porcupine (wake word)", "Picovoice · needs access key")
            LicenseRow("JNA", "LGPL 2.1 / Apache 2.0")

            LicenseSection("Bundled source / assets")
            LicenseRow("okhttp3 stubs", "Apache 2.0")
            LicenseRow("Ludo Classic (assets/ludoclassic)", "MIT")

            Spacer(Modifier.height(24.dp))
            Text(
                "No API keys ship in the app. assets/keys.json is empty and gitignored.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LicenseSection(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun LicenseRow(name: String, license: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
        Text(license, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
    }
}
