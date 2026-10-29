package com.axzora.os

import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.data.SettingsStore
import com.axzora.os.data.UserSettings
import com.axzora.os.ui.Atmospheres
import com.axzora.os.ui.ClockBlock
import com.axzora.os.ui.ClockStyles
import com.axzora.os.ui.applyAtmosphereWallpaper
import kotlinx.coroutines.launch

/**
 * The style studio: everything about how the phone's face looks, in one
 * place, arranged around a live preview.
 *
 * The preview is not a picture of the design — it *is* the design, rendered
 * by the same [ClockBlock] and atmosphere painter the home screen and the
 * wallpaper use. Whatever this small phone shows is exactly what the big
 * one will do.
 */
@Composable
fun StyleScreen(
    settings: UserSettings,
    store: SettingsStore,
    clock: Triple<String, String, String>,
    greetingLine: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val onBg = MaterialTheme.colorScheme.onBackground

    var applied by remember { mutableStateOf("") }
    val atmIndex = settings.atmosphere.coerceIn(0, Atmospheres.lastIndex)
    val atm = Atmospheres[atmIndex]
    val painted = atmIndex > 0

    Column(
        Modifier.fillMaxSize()
            .padding(top = 48.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                    tint = onBg,
                )
            }
            Column {
                Text(
                    "Home & lock",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 30.sp, fontStyle = FontStyle.Italic,
                    ),
                    color = onBg,
                )
                Text(
                    "The face your phone wears",
                    fontSize = 12.sp,
                    color = onBg.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        /* ---- the living preview ---- */
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(1.5.dp, onBg.copy(alpha = 0.18f)),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(0.62f).aspectRatio(9f / 18.4f),
            ) {
                Crossfade(targetState = atmIndex, animationSpec = tween(320), label = "atmPrev") { i ->
                    val a = Atmospheres[i]
                    Box(
                        Modifier.fillMaxSize().background(
                            if (i == 0) {
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background,
                                        MaterialTheme.colorScheme.background,
                                        accent.copy(alpha = 0.18f),
                                    ),
                                )
                            } else {
                                Brush.verticalGradient(
                                    0f to a.top, 0.55f to a.mid, 1f to a.bottom,
                                )
                            },
                        ),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Spacer(Modifier.height(10.dp))
                            ClockBlock(
                                style = settings.clockStyle,
                                size = settings.clockSize,
                                time = clock.first,
                                date = clock.second,
                                meridiem = clock.third,
                                greetingLine = greetingLine,
                                accent = accent,
                                onBg = Color(0xFFF7F5F2),
                                scale = 0.52f,
                            )
                        }
                        // A hint of the dock, so the preview reads as a phone.
                        Box(
                            Modifier.align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .fillMaxWidth(0.82f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.07f)),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Column(Modifier.padding(horizontal = 20.dp)) {

            /* ---- clock faces ---- */
            Text(
                "Clock",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 20.sp, fontStyle = FontStyle.Italic,
                ),
                color = onBg,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClockStyles.names.forEachIndexed { i, name ->
                    val sel = settings.clockStyle == i
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            if (sel) 2.dp else 1.dp,
                            if (sel) accent else MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier
                            .width(128.dp)
                            .clickable { scope.launch { store.setClockStyle(i) } },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Box(Modifier.height(58.dp)) {
                                ClockBlock(
                                    style = i,
                                    size = 1,
                                    time = clock.first,
                                    date = clock.second,
                                    meridiem = clock.third,
                                    greetingLine = "",
                                    accent = accent,
                                    onBg = MaterialTheme.colorScheme.onSurface,
                                    scale = 0.34f,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    name, fontSize = 12.sp,
                                    color = onBg.copy(alpha = if (sel) 1f else 0.6f),
                                )
                                Spacer(Modifier.weight(1f))
                                if (sel) {
                                    Icon(
                                        Icons.Rounded.CheckCircle, null,
                                        tint = accent, modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Small", "Medium", "Large").forEachIndexed { i, label ->
                    val sel = settings.clockSize == i
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (sel) accent else MaterialTheme.colorScheme.surface,
                        border = if (sel) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.clickable { scope.launch { store.setClockSize(i) } },
                    ) {
                        Text(
                            label,
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            fontSize = 12.sp,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            /* ---- atmospheres ---- */
            Text(
                "Atmosphere",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 20.sp, fontStyle = FontStyle.Italic,
                ),
                color = onBg,
            )
            Text(
                "The sky behind everything",
                fontSize = 12.sp, color = onBg.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Atmospheres.forEachIndexed { i, a ->
                    val sel = atmIndex == i
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .clickable { scope.launch { store.setAtmosphere(i) } }
                            .padding(4.dp),
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape)
                                .background(
                                    if (i == 0) {
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                MaterialTheme.colorScheme.background,
                                            ),
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            0f to a.top, 0.55f to a.mid, 1f to a.bottom,
                                        )
                                    },
                                )
                                .border(
                                    if (sel) 2.5.dp else 1.dp,
                                    if (sel) accent else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == 0) {
                                Icon(
                                    Icons.Rounded.Wallpaper, null,
                                    tint = onBg.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Box(
                                    Modifier.size(11.dp).clip(CircleShape)
                                        .background(a.glow),
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            a.name, fontSize = 10.5.sp,
                            color = onBg.copy(alpha = if (sel) 1f else 0.55f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            /* ---- and onto the real lock screen ---- */
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Lock, null,
                            tint = accent, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Wear it on the lock screen too",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (painted) {
                            "Sets \\u201C${atm.name}\\u201D as the phone's real wallpaper — " +
                                "the same sky before and after you unlock."
                        } else {
                            "Pick a painted atmosphere above to put it on the " +
                                "lock screen as well."
                        },
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = painted,
                            onClick = {
                                scope.launch {
                                    applied = if (applyAtmosphereWallpaper(context, atm, lock = true, home = false)) {
                                        "Lock screen updated."
                                    } else "Couldn't change the wallpaper on this phone."
                                }
                            },
                        ) { Text("Lock screen", fontSize = 12.sp) }
                        OutlinedButton(
                            enabled = painted,
                            onClick = {
                                scope.launch {
                                    applied = if (applyAtmosphereWallpaper(context, atm, lock = true, home = true)) {
                                        "Lock and home wallpaper updated."
                                    } else "Couldn't change the wallpaper on this phone."
                                }
                            },
                        ) { Text("Both", fontSize = 12.sp) }
                    }
                    if (applied.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            applied, fontSize = 12.sp,
                            color = accent,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- the system's own doors ---- */
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_SET_WALLPAPER)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Wallpaper, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Choose your own wallpaper",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        Icons.Rounded.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Lock type & security",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "PIN, fingerprint and face unlock live in Android's own settings",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}
