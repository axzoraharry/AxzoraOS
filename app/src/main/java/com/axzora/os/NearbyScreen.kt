package com.axzora.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.axzora.os.nearby.NearbyManager
import com.axzora.os.nearby.NearbyMessage
import com.axzora.os.nearby.Peer
import com.axzora.os.wallet.Rail
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Offline mode: finds other Axzora phones over radio and exchanges messages,
 * files and Happy Paisa with no server, router or internet involved.
 */
@Composable
fun NearbyScreen(displayName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { com.axzora.os.data.SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val nearby = remember { NearbyManager(context) }
    val wallet = remember { WalletStore(context) }

    val peers by nearby.peers.collectAsState()
    val voice by nearby.voice.collectAsState()
    val messages by nearby.messages.collectAsState()
    val status by nearby.status.collectAsState()
    val transfer by nearby.transfer.collectAsState()
    val gmsOk by nearby.gmsOk.collectAsState()

    var input by remember { mutableStateOf("") }
    var showMoney by remember { mutableStateOf(false) }
    var moneyAmount by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val accent = MaterialTheme.colorScheme.primary
    val connected = peers.count { it.connected }

    // Nearby Connections needs radio + (below API 33) location permission to
    // scan; without them discovery silently returns nothing.
    // Everything worth asking for…
    val requested = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)          // nearby voice calls
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.NEARBY_WIFI_DEVICES")
        }
    }

    // …of which these are genuinely required: SCAN finds others, ADVERTISE lets
    // others find us, CONNECT opens the link. Only NEARBY_WIFI_DEVICES is
    // optional — it upgrades the transfer to Wi-Fi Direct for speed, and
    // without it Nearby still works over Bluetooth.
    val required = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    fun hasRequired() = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasRequired()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasRequired() }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
            }.getOrNull() ?: "file"
            nearby.sendFile(uri, name)
        }
    }

    // Money that arrives over radio is banked straight away.
    LaunchedEffect(Unit) {
        nearby.onMoneyReceived = { hp, from, note ->
            scope.launch {
                wallet.credit(hp, from, note.ifBlank { "Nearby transfer" }, Rail.BLUETOOTH)
            }
        }
    }

    // Re-checked on every recomposition so flipping Bluetooth back on in the
    // system settings clears the warning without leaving this screen.
    var radio by remember { mutableStateOf(nearby.radioState()) }
    LaunchedEffect(granted) {
        while (true) {
            radio = nearby.radioState()
            kotlinx.coroutines.delay(2000)
        }
    }

    // Nearby needs Google Play services; check on entry so a missing/disabled/
    // stale service is explained immediately rather than as a scan failure.
    LaunchedEffect(granted) { nearby.refreshGms() }

    // Restore remembered devices before scanning starts, and write the set back
    // whenever it changes, so pairing survives closing the app.
    LaunchedEffect(Unit) {
        nearby.loadTrusted(settingsStore.settings.first().trustedPeers)
        nearby.onAutoReconnect = {
            scope.launch { settingsStore.setTrustedPeers(nearby.trustedNames()) }
        }
    }
    LaunchedEffect(peers) {
        settingsStore.setTrustedPeers(nearby.trustedNames())
    }

    DisposableEffect(granted, displayName, radio.ready) {
        if (granted && radio.ready) nearby.start(displayName)
        onDispose { nearby.stop() }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(top = 44.dp)) {

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text("Nearby", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    when {
                        !granted -> "Permission needed"
                        connected > 0 -> "$connected connected · no internet used"
                        peers.isNotEmpty() -> "${peers.size} found — tap to connect"
                        else -> "Searching…"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
            Icon(
                Icons.Rounded.WifiTethering, null,
                tint = if (connected > 0) Color(0xFF22C55E) else accent.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(12.dp))
        }

        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.BluetoothSearching, null, modifier = Modifier.size(56.dp), tint = accent)
                Spacer(Modifier.height(16.dp))
                Text("Nearby needs Bluetooth and location access",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android requires location permission to scan for nearby devices. " +
                        "Your location is never sent anywhere — this is only used to find phones around you.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = { permLauncher.launch(requested.toTypedArray()) }) {
                    Text("Allow")
                }
            }
            return@Column
        }

        // Bluetooth or Location switched off means discovery silently finds
        // nothing, so say so plainly and offer the toggle rather than spinning.
        if (!radio.ready) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            !radio.bluetoothOn && !radio.locationServicesOn ->
                                "Bluetooth and Location are off"
                            !radio.bluetoothOn -> "Bluetooth is off"
                            else -> "Location is off"
                        },
                        fontSize = 13.sp, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    TextButton(onClick = {
                        val action = if (!radio.bluetoothOn)
                            android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                        else android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        runCatching {
                            context.startActivity(
                                android.content.Intent(action)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("Turn on") }
                }
            }
        }

        // Google Play services is required by the Nearby radio API. If it's not
        // usable, scanning can never work, so offer the exact fix rather than a
        // scan that just keeps failing.
        if (!gmsOk) {
            val gmsReason = nearby.gmsState().reason
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null, tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        gmsReason + " Nearby needs it to scan.",
                        fontSize = 13.sp, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    TextButton(onClick = {
                        nearby.gmsFixIntent()?.let { runCatching { context.startActivity(it) } }
                    }) { Text("Fix") }
                }
            }
        }

        // Peers strip
        if (peers.isNotEmpty()) {
            LazyColumn(
                Modifier.heightIn(max = 170.dp).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peers, key = { it.endpointId }) { p ->
                    PeerRow(p, accent, onCall = if (p.connected && voice == null) ({ nearby.callPeer(p) }) else null) {
                        if (p.connected) nearby.disconnect(p) else nearby.connect(p)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        voice?.let { v -> VoiceBar(v, accent,
            onAccept = { nearby.acceptCall() }, onDecline = { nearby.declineCall() }, onEnd = { nearby.endCall() },
            onMute = { nearby.toggleVoiceMute() }, onSpeaker = { nearby.toggleVoiceSpeaker() }) }

        if (status.isNotBlank()) {
            // Selectable so a failure can be copied and reported verbatim
            // rather than retyped from memory.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    status, fontSize = 12.sp, color = accent,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        transfer?.let { t ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("${t.name} · ${t.percent}%", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                LinearProgressIndicator(
                    progress = { t.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages) { m -> NearbyBubble(m, accent) }
        }

        if (showMoney) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = moneyAmount,
                    onValueChange = { moneyAmount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("HP to send") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val hp = moneyAmount.toDoubleOrNull()
                    if (hp != null && hp > 0) {
                        scope.launch {
                            if (wallet.debit(hp, "Nearby", input, Rail.BLUETOOTH)) {
                                nearby.sendMoney(hp, input)
                                moneyAmount = ""; input = ""; showMoney = false
                            }
                        }
                    }
                }) { Text("Send") }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { filePicker.launch("*/*") }) {
                Icon(Icons.Rounded.AttachFile, "Send file", tint = accent)
            }
            IconButton(onClick = { showMoney = !showMoney }) {
                Icon(Icons.Rounded.Savings, "Send Happy Paisa", tint = accent)
            }
            IconButton(onClick = {
                scope.launch {
                    val loc = com.axzora.os.location.LocationRepository(context).let {
                        it.currentLocation() ?: it.lastKnown()
                    }
                    if (loc != null) nearby.sendLocation(loc.latitude, loc.longitude)
                }
            }) {
                Icon(Icons.Rounded.MyLocation, "Share my location", tint = accent)
            }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Message nearby…") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank()) { nearby.sendText(input.trim()); input = "" }
                }),
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = {
                if (input.isNotBlank()) { nearby.sendText(input.trim()); input = "" }
            }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
        }
    }
}

/** The nearby call strip: ringing / incoming / live, with the in-call controls. */
@Composable
private fun VoiceBar(
    v: NearbyManager.VoiceCall, accent: Color,
    onAccept: () -> Unit, onDecline: () -> Unit, onEnd: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(v.state) { while (v.state == "active") { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val secs = if (v.startedAt > 0) ((now - v.startedAt) / 1000).coerceAtLeast(0) else 0
    Surface(
        shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Phone, null, tint = accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (v.state) { "incoming" -> "${v.name} is calling"; "ringing" -> "Calling ${v.name}…"; else -> v.name },
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    when (v.state) { "active" -> "%d:%02d · nearby call, no internet".format(secs / 60, secs % 60); "ringing" -> "Ringing over Bluetooth / Wi-Fi Direct"; else -> "Nearby call — no internet needed" },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            when (v.state) {
                "incoming" -> {
                    FilledIconButton(onClick = onAccept, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF22C55E))) { Icon(Icons.Rounded.Call, "Accept", tint = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = onDecline, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5484D))) { Icon(Icons.Rounded.CallEnd, "Decline", tint = Color.White) }
                }
                "active" -> {
                    IconButton(onClick = onMute) { Icon(if (v.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute", tint = if (v.muted) accent else MaterialTheme.colorScheme.onBackground) }
                    IconButton(onClick = onSpeaker) { Icon(Icons.Rounded.VolumeUp, "Speaker", tint = if (v.speaker) accent else MaterialTheme.colorScheme.onBackground) }
                    FilledIconButton(onClick = onEnd, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5484D))) { Icon(Icons.Rounded.CallEnd, "End", tint = Color.White) }
                }
                else -> FilledIconButton(onClick = onEnd, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5484D))) { Icon(Icons.Rounded.CallEnd, "Cancel", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun PeerRow(p: Peer, accent: Color, onCall: (() -> Unit)? = null, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(
            1.dp,
            if (p.connected) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.07f),
        ),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(
                    Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)))
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.name.trim().take(1).uppercase().ifBlank { "?" },
                    color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    when {
                        p.connected -> "Connected"
                        p.connecting -> "Connecting…"
                        else -> "Tap to connect"
                    },
                    fontSize = 11.sp,
                    color = when {
                        p.connected -> Color(0xFF22C55E)
                        p.connecting -> accent
                        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    },
                )
            }
            if (onCall != null) {
                FilledIconButton(onClick = onCall, modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF22C55E))) {
                    Icon(Icons.Rounded.Call, "Call", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            if (p.connecting) {
                CircularProgressIndicator(
                    Modifier.size(18.dp), strokeWidth = 2.dp, color = accent,
                )
            } else {
                Icon(
                    if (p.connected) Icons.Rounded.LinkOff else Icons.Rounded.Link,
                    null, tint = accent, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun NearbyBubble(m: NearbyMessage, accent: Color) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (m.mine) accent else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!m.mine) {
                    Text(m.from, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
                }
                when {
                    m.hp != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Savings, null, modifier = Modifier.size(16.dp),
                            tint = if (m.mine) MaterialTheme.colorScheme.onPrimary else accent)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "%.3f HP".format(m.hp) + if (m.text.isNotBlank()) " · ${m.text}" else "",
                            fontWeight = FontWeight.Bold,
                            color = if (m.mine) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    m.lat != null && m.lon != null -> Column(
                        Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}"),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.MyLocation, null, modifier = Modifier.size(16.dp),
                                tint = if (m.mine) MaterialTheme.colorScheme.onPrimary else accent)
                            Spacer(Modifier.width(6.dp))
                            Text("Shared location", fontWeight = FontWeight.Medium,
                                color = if (m.mine) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                        Text(
                            "%.5f, %.5f · tap to open".format(m.lat, m.lon),
                            fontSize = 11.sp,
                            color = if (m.mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    m.fileName != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.InsertDriveFile, null, modifier = Modifier.size(16.dp),
                            tint = if (m.mine) MaterialTheme.colorScheme.onPrimary else accent)
                        Spacer(Modifier.width(6.dp))
                        Text(m.fileName,
                            color = if (m.mine) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface)
                    }
                    else -> Text(
                        m.text,
                        color = if (m.mine) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
