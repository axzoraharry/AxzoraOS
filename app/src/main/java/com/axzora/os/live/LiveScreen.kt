package com.axzora.os.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.wallet.WalletNet
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live rooms: a host broadcasts (an ordinary WebRTC group call), and any
 * Axzora install can watch and tip the host in Happy Paisa. This screen is the
 * launcher/directory — it lists who is live, starts a broadcast, and fires the
 * tip. The actual audio/video is the group-call machinery chosen by [onGoLive]
 * and [onJoinRoom].
 */
@Composable
fun LiveScreen(
    serverUrl: String,
    viewerName: String,
    onGoLive: (title: String) -> Unit,
    onJoinRoom: (LiveRoom) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { LiveClient { serverUrl } }
    val wallet = remember { WalletStore(context) }
    var rooms by remember { mutableStateOf<List<LiveRoom>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var tipTarget by remember { mutableStateOf<LiveRoom?>(null) }

    LaunchedEffect(serverUrl) {
        while (true) {
            rooms = client.list()
            delay(4000)
        }
    }

    fun sendTip(target: LiveRoom, amount: String) {
        val hp = amount.toDoubleOrNull()
        if (hp == null || hp <= 0) { status = "Enter a valid HP amount."; return }
        scope.launch {
            val uid = wallet.ensureUid()
            val res = runCatching {
                WalletNet { serverUrl }.send(
                    uid, viewerName.ifBlank { "Me" }, target.host, hp,
                    "Live tip to ${target.hostName}",
                )
            }.getOrElse { com.axzora.os.wallet.SendResult.Failed(it.message ?: "network error") }
            status = when (res) {
                is com.axzora.os.wallet.SendResult.Ok -> "Sent ${hp} HP to ${res.toName}."
                is com.axzora.os.wallet.SendResult.NoSuchId -> "No Axzora ID for ${target.hostName}."
                is com.axzora.os.wallet.SendResult.Failed -> res.reason
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 46.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Live", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Go live", fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Live title") },
                        placeholder = { Text("e.g. Morning music") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { if (title.isNotBlank()) onGoLive(title.trim()) },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.Videocam, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start your live", fontSize = 15.sp)
                    }
                }
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            Text("Live now", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
        }

        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("Nobody is live right now.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rooms, key = { it.roomId }) { room ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape)
                                .background(Color(0xFFE4353F)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(room.title, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground)
                                Text("${room.hostName} · ${room.viewers} watching",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
                            }
                            Button(
                                onClick = { onJoinRoom(room) },
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Join") }
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { tipTarget = room }) {
                                Icon(Icons.Rounded.MonetizationOn, "Tip",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    tipTarget?.let { target ->
        TipDialog(
            hostName = target.hostName,
            onDismiss = { tipTarget = null },
            onSend = { amt -> sendTip(target, amt); tipTarget = null },
        )
    }
}

@Composable
private fun TipDialog(
    hostName: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tip ${hostName.ifBlank { "the host" }}") },
        text = {
            Column {
                Text("Send Happy Paisa to say thanks for the live.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("HP amount") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(amount) }, enabled = amount.toDoubleOrNull()?.let { it > 0 } == true) {
                Text("Send tip")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
