package com.axzora.os

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.data.UserSettings
import com.axzora.os.launcher.AppInfo
import com.axzora.os.launcher.AppRepository
import com.axzora.os.ui.AuroraBackground
import com.axzora.os.ui.ClockBlock
import com.axzora.os.ui.pressBounce
import com.axzora.os.ui.rememberDimens
import com.axzora.os.ui.revealFrom
import com.axzora.os.ui.tiltParallax
import kotlinx.coroutines.delay

/* ------------------------------------------------------------------ */
/*  Launcher home screen + its sub-composables.                        */
/*  Extracted from MainActivity.kt to keep the shell hub readable.     */
/* ------------------------------------------------------------------ */


@Composable
internal fun HomeScreen(
    settings: UserSettings,
    apps: List<AppInfo>,
    appRepo: AppRepository,
    onLaunch: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    var clock by remember { mutableStateOf(nowText()) }
    LaunchedEffect(Unit) { while (true) { clock = nowText(); delay(1000) } }

    // Before the user has picked favourites, show the apps a phone actually
    // needs — dialer, messages, browser, camera, contacts — then fill the
    // rest alphabetically. Taking the first N by name left a home screen with
    // no way to make a call, which is no use as a phone's home screen.
    val essentials = remember(apps) { appRepo.essentialPackages() }
    val favorites = apps.filter { it.packageName in settings.favoriteApps }
        .ifEmpty {
            val slots = settings.gridColumns * 2
            val first = essentials.mapNotNull { pkg -> apps.find { it.packageName == pkg } }
            (first + apps.filterNot { it in first }).take(slots)
        }

    // "Mr Happy suggests" — the apps you most likely need right now, from your
    // real launch history + time of day. Honest heuristics, no neural network.
    val recent by appRepo.recentPackages.collectAsState()
    val suggested = remember(apps, recent) { suggestedApps(apps, recent) }

    val accent = MaterialTheme.colorScheme.primary
    val dim = rememberDimens()
    val onBg = MaterialTheme.colorScheme.onBackground
    val dark = settings.darkMode

    // Swipe up anywhere on Home to open the app grid — the gesture every
    // launcher has taught people's thumbs. The content follows the finger a
    // little on the way, so the drawer feels pulled in rather than switched.
    val anim = com.axzora.os.ui.LocalAnimLevel.current
    var pull by remember { mutableStateOf(0f) }
    val pullBack by androidx.compose.animation.core.animateFloatAsState(
        targetValue = pull,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.8f, stiffness = 500f,
        ),
        label = "pull",
    )
    val flingThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 90.dp.toPx() }

    val atmosphere = com.axzora.os.ui.Atmospheres[
        settings.atmosphere.coerceIn(0, com.axzora.os.ui.Atmospheres.lastIndex)
    ].takeIf { settings.atmosphere > 0 }

    val onSurface = MaterialTheme.colorScheme.onSurface

    AuroraBackground(accent = accent, dark = dark, atmosphere = atmosphere) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = pullBack }
                .tiltParallax(7.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 60.dp, bottom = 20.dp)
        ) {
            /* ---- header: the clock face chosen in Home & lock ---- */
            com.axzora.os.ui.ClockBlock(
                style = settings.clockStyle,
                size = settings.clockSize,
                time = clock.first,
                date = clock.second,
                meridiem = clock.third,
                greetingLine = greeting() +
                    (if (settings.displayName.isNotBlank()) ", ${settings.displayName}" else ""),
                accent = accent,
                onBg = onBg,
            )


            /* ---- Mr Happy AI core ---- */
            val coreCtx = androidx.compose.ui.platform.LocalContext.current
            var battery by remember { mutableStateOf(batteryPct(coreCtx)) }
            LaunchedEffect(Unit) {
                while (true) { battery = batteryPct(coreCtx); delay(30000) }
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.5f else 0.88f),
                border = BorderStroke(1.2.dp, Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.7f), accent.copy(alpha = 0.14f))
                )),
                modifier = Modifier.fillMaxWidth().revealFrom(0)
                    .pressBounce(pressedScale = 0.99f) { openMrHappy(coreCtx) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // a softly pulsing live dot
                        androidx.compose.animation.core.rememberInfiniteTransition(label = "ai")
                            .animateFloat(
                                0.5f, 1f,
                                androidx.compose.animation.core.infiniteRepeatable(
                                    androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing),
                                    androidx.compose.animation.core.RepeatMode.Reverse,
                                ),
                                label = "pulse",
                            ).value.let { pulseAlpha ->
                            Text("●", fontSize = 15.sp, color = accent.copy(alpha = pulseAlpha))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("MR HAPPY AI CORE", fontSize = 10.sp, letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold, color = accent)
                        Spacer(Modifier.weight(1f))
                        Text("● Online", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF7CFC9C))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Voice command:", fontSize = 12.sp,
                            color = onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.width(6.dp))
                        Text("“Hey Mr Happy”", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = onSurface)
                        Spacer(Modifier.weight(1f))
                        Text("● Listening", fontSize = 11.sp, color = accent)
                    }
                    Spacer(Modifier.height(10.dp))
                    // capability row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Device", "Apps", "Automate", "Search", "Memory", "Offline").forEach { cap ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = accent.copy(alpha = 0.12f),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(cap, fontSize = 9.sp, color = onSurface.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(vertical = 5.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // live status row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("⚡", "Power", if (battery == -1) "—" else "$battery%")
                        Spacer(Modifier.weight(1f))
                        StatusPill("🧠", "Memory", "Ok")
                        Spacer(Modifier.weight(1f))
                        StatusPill("📱", "Devices", "1")
                        Spacer(Modifier.weight(1f))
                        StatusPill("🔒", "Privacy", "On")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- greeting + daily context ---- */
            val heroCtx = androidx.compose.ui.platform.LocalContext.current
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.5f else 0.88f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth().revealFrom(1).clickable { openMrHappy(heroCtx) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${greeting()}${if (settings.displayName.isNotBlank()) ", ${settings.displayName}" else ""}",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    // daily context line: weather + battery + messages
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContextChip("🌤", "weather", accent)
                        Spacer(Modifier.width(8.dp))
                        ContextChip("🔋", if (battery == -1) "—" else "$battery%", accent)
                        Spacer(Modifier.width(8.dp))
                        ContextChip("💬", "messages", accent)
                        Spacer(Modifier.width(8.dp))
                        ContextChip("🗓", "tasks", accent)
                        Spacer(Modifier.weight(1f))
                        Text("Smart", fontSize = 10.sp, color = accent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).background(accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Say “Hey Mr Happy” to control the phone, automate tasks, check the wallet, or manage devices.",
                        fontSize = 11.sp, lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- quick action chips ---- */
            // Two rows of two, so a fourth action - your devices - gets the
            // same weight as the rest instead of being squeezed into a corner.
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickChip(Icons.Rounded.Devices, "Devices", Modifier.weight(1f).revealFrom(2)) { onNavigate(Screen.DEVICES) }
                QuickChip(Icons.Rounded.LocationOn, "Family map", Modifier.weight(1f).revealFrom(3)) { onNavigate(Screen.LOCATION) }
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickChip(Icons.Rounded.WifiTethering, "Offline", Modifier.weight(1f).revealFrom(4)) { onNavigate(Screen.NEARBY) }
                QuickChip(Icons.Rounded.CurrencyRupee, "UPI", Modifier.weight(1f).revealFrom(5)) { onNavigate(Screen.PAY) }
            }
            Spacer(Modifier.height(9.dp))
            val orCtx = androidx.compose.ui.platform.LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickChip(Icons.Rounded.Widgets, "OpenRoom", Modifier.weight(1f).revealFrom(6)) {
                    runCatching {
                        orCtx.startActivity(
                            Intent(orCtx, com.axzora.os.openroom.OpenRoomActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                QuickChip(Icons.Rounded.AutoAwesome, "Mr Happy", Modifier.weight(1f).revealFrom(7)) {
                    openMrHappy(orCtx)
                }
                QuickChip(Icons.Rounded.Videocam, "Live", Modifier.weight(1f).revealFrom(7)) {
                    onNavigate(Screen.LIVE)
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickChip(Icons.Rounded.Hub, "Open Sources", Modifier.weight(1f).revealFrom(8)) {
                    runCatching {
                        orCtx.startActivity(
                            Intent(orCtx, com.axzora.os.sources.OpenSourcesActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                QuickChip(Icons.Rounded.SportsEsports, "Games", Modifier.weight(1f).revealFrom(9)) {
                    runCatching {
                        orCtx.startActivity(
                            Intent(orCtx, com.axzora.os.games.GamesActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickChip(Icons.Rounded.GraphicEq, "SoundMesh", Modifier.weight(1f).revealFrom(9)) {
                    onNavigate(Screen.SOUNDMESH)
                }
                // God's Eye View — open the live OSINT globe INSIDE the app (a
                // geo-enabled WebView, so it can fly to your location), not the
                // external browser.
                QuickChip(Icons.Rounded.Public, "God's Eye", Modifier.weight(1f).revealFrom(9)) {
                    runCatching {
                        orCtx.startActivity(
                            Intent(orCtx, com.axzora.os.godseye.GodsEyeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (settings.favoriteApps.isEmpty()) "YOUR APPS" else "FAVOURITES",
                    style = MaterialTheme.typography.labelSmall,
                    color = onBg.copy(alpha = 0.42f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "All apps",
                    fontSize = 12.sp,
                    color = accent,
                    modifier = Modifier.clickable { onNavigate(Screen.DRAWER) },
                )
            }
            Spacer(Modifier.height(12.dp))

            if (apps.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp)
                }
            } else {
                LazyVerticalGrid(
                    // The stored preference is a floor; wider screens get more.
                    columns = GridCells.Fixed(maxOf(settings.gridColumns, dim.gridColumns)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(favorites, key = { _, app -> app.packageName }) { i, app ->
                        AppTile(app, index = i) { onLaunch(app.packageName) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            /* ---- Mr Happy suggests (predictive row) ---- */
            if (suggested.isNotEmpty()) {
                Text("✨ Mr Happy suggests", fontSize = 11.sp, color = accent,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(6.dp))
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(suggested, key = { it.packageName }) { app ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.pressBounce(pressedScale = 0.9f) { onLaunch(app.packageName) },
                        ) {
                            Image(app.icon, app.label,
                                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)))
                            Spacer(Modifier.height(4.dp))
                            Text(app.label, fontSize = 9.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            /* ---- dock ---- */
            Surface(
                shape = RoundedCornerShape(34.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.62f else 0.92f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.13f else 0.30f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(vertical = 11.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Phone and Messages lead, because this is a phone's home
                    // screen before it is anything else — both open whichever
                    // app the user has set as their default. Each item takes
                    // an equal share of the row so all seven fit a narrow
                    // phone without any being pushed off the edge.
                    val slot = Modifier.weight(1f)
                    DockItem(Icons.Rounded.Call, "Phone", dim.dockIcon, slot) { appRepo.openDialer() }
                    DockItem(Icons.Rounded.Sms, "SMS", dim.dockIcon, slot) { appRepo.openSms() }
                    DockItem(Icons.Rounded.AutoAwesome, "AI Core", dim.dockIcon, slot) { onNavigate(Screen.AGI) }
                    DockItem(Icons.Rounded.Groups, "People", dim.dockIcon, slot) { onNavigate(Screen.PEOPLE) }
                    DockItem(Icons.AutoMirrored.Rounded.Chat, "Chats", dim.dockIcon, slot) { onNavigate(Screen.MESSAGES) }
                    DockItem(Icons.Rounded.AccountBalanceWallet, "Wallet", dim.dockIcon, slot) { onNavigate(Screen.WALLET) }
                    DockItem(Icons.Rounded.Settings, "Settings", dim.dockIcon, slot) { onNavigate(Screen.SETTINGS) }
                }
            }
        }
    }
}


@Composable
private fun ContextChip(icon: String, label: String, accent: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.1f),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        }
    }
}


@Composable
private fun StatusPill(icon: String, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 16.sp)
        Text(label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}


@Composable
private fun QuickChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = modifier.height(46.dp).pressBounce(pressedScale = 0.94f) { onClick() },
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
        }
    }
}


@Composable
private fun DockItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconSize: Dp = 24.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val anim = com.axzora.os.ui.LocalAnimLevel.current
    val hf = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && anim > 0) 0.86f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.55f, stiffness = 900f,
        ),
        label = "dock",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null) {
                if (anim > 0) runCatching {
                    hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                onClick()
            }
            // Horizontal padding is small so seven items still fit across a
            // narrow phone; the row's own spacing does the separating.
            .padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize))
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
    }
}

/** Predict the next apps: real launch history first, then time-of-day context. */
private fun suggestedApps(apps: List<AppInfo>, recent: List<String>): List<AppInfo> {
    if (apps.isEmpty()) return emptyList()
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val timePrefs = when {
        hour in 5..10 -> listOf("message", "whatsapp", "phone", "dialer", "calendar", "note")
        hour in 11..16 -> listOf("browser", "gmail", "mail", "file", "whatsapp", "chrome")
        hour in 17..22 -> listOf("youtube", "music", "game", "instagram", "whatsapp", "video")
        else -> listOf("clock", "message", "youtube", "whatsapp", "photo")
    }
    val seen = LinkedHashMap<String, AppInfo>()
    recent.forEach { pkg -> apps.find { it.packageName == pkg }?.let { seen.putIfAbsent(pkg, it) } }
    timePrefs.forEach { q ->
        apps.firstOrNull { it.label.lowercase().contains(q) }?.let { seen.putIfAbsent(it.packageName, it) }
    }
    return seen.values.take(6)
}
