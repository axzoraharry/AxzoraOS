package com.axzora.os

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.core.net.toUri
import com.axzora.os.wallet.Person
import com.axzora.os.wallet.WalletNet
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Directory of everyone running Axzora. Installing the app is what puts a
 * person here — there is no contact upload and no phone-book scraping.
 */
@Composable
fun PeopleScreen(
    serverUrl: String,
    onBack: () -> Unit,
    onPay: (Person) -> Unit,
    onMessage: (Person) -> Unit,
    onCall: (Person) -> Unit,
    onVideoCall: (Person) -> Unit,
    onGroupCall: (List<Person>, video: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallet = remember { WalletStore(context) }
    val net = remember(serverUrl) { WalletNet { serverUrl } }

    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    var status by remember { mutableStateOf("Looking for Axzora users…") }
    var myUid by remember { mutableStateOf("") }
    var dialId by remember { mutableStateOf("") }

    /*
     * Picking people for a group call.
     *
     * Everyone holds a direct connection to everyone else, so each extra
     * person is another video stream for every phone to encode and decode.
     * Three others is where a phone still keeps up; past that the picture
     * starts breaking up for whoever has the weakest handset.
     */
    var picking by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    val groupMax = 3

    suspend fun refresh() {
        val uid = wallet.ensureUid()
        myUid = uid
        // An empty list means one of two very different things; tell them apart
        // so "can't reach the server" never reads as "nobody uses this app".
        val reachable = net.lookup(uid) != null || net.register(uid, "Axzora user")
        val list = net.directory(uid)
        people = list
        status = when {
            list.isNotEmpty() -> ""
            !reachable -> "Can't reach the Axzora network. Check your internet — you can still use Nearby offline."
            else -> "No one else has installed Axzora yet. Share the app — they'll appear here automatically."
        }
    }

    LaunchedEffect(serverUrl) {
        refresh()
        while (true) { delay(15_000); refresh() }
    }

    val accent = MaterialTheme.colorScheme.primary

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
                Text("People", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    when {
                        picking && chosen.isEmpty() -> "Pick up to $groupMax people"
                        picking -> "${chosen.size} of $groupMax chosen"
                        people.isEmpty() -> "Axzora users"
                        else -> "${people.size} on Axzora"
                    },
                    fontSize = 12.sp,
                    color = if (picking) accent
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
            if (people.isNotEmpty()) {
                IconButton(onClick = {
                    picking = !picking
                    if (!picking) chosen.clear()
                }) {
                    Icon(
                        Icons.Rounded.Groups,
                        if (picking) "Cancel group call" else "New group call",
                        tint = if (picking) MaterialTheme.colorScheme.error else accent,
                    )
                }
            }
            IconButton(onClick = { scope.launch { refresh() } }) {
                Icon(Icons.Rounded.Refresh, "Refresh", tint = accent)
            }
        }

        if (status.isNotBlank()) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Groups, null, modifier = Modifier.size(56.dp),
                    tint = accent.copy(alpha = 0.6f))
                Spacer(Modifier.height(16.dp))
                Text(status, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (myUid.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    Text("Your ID", fontSize = 11.sp, letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                    Text(myUid, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = accent)
                }

                // Calling shouldn't depend on the directory having loaded — if
                // you know someone's ID you can reach them directly.
                Spacer(Modifier.height(28.dp))
                Text("Or call someone by their ID", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = dialId, onValueChange = { dialId = it },
                        placeholder = { Text("AXZ-XXXX-XXXX") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val id = com.axzora.os.wallet.AxzoraId.normalize(dialId)
                            if (com.axzora.os.wallet.AxzoraId.isValid(id)) {
                                onCall(Person(uid = id, name = id, phone = "", seen = 0))
                            }
                        },
                        enabled = com.axzora.os.wallet.AxzoraId.isValid(
                            com.axzora.os.wallet.AxzoraId.normalize(dialId)
                        ),
                    ) { Icon(Icons.Rounded.Call, "Call") }
                }
            }
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(people, key = { it.uid }) { p ->
                val ticked = p.uid in chosen
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (ticked) accent.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    border = BorderStroke(
                        1.dp,
                        if (ticked) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.07f),
                    ),
                    modifier = Modifier.fillMaxWidth().then(
                        if (!picking) Modifier else Modifier.clickable {
                            if (ticked) chosen.remove(p.uid)
                            // Silently ignoring the tap past the limit reads
                            // as a broken row; the count in the header is the
                            // only explanation needed, so stop at the cap.
                            else if (chosen.size < groupMax) chosen.add(p.uid)
                        }
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(46.dp).clip(CircleShape).background(
                                    Brush.linearGradient(
                                        listOf(accent, accent.copy(alpha = 0.55f))
                                    )
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    p.name.trim().take(1).uppercase().ifBlank { "?" },
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground)
                                Text(p.uid, fontSize = 11.sp, letterSpacing = 0.5.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                            }
                            if (picking) {
                                Checkbox(
                                    checked = ticked,
                                    onCheckedChange = { on ->
                                        if (!on) chosen.remove(p.uid)
                                        else if (chosen.size < groupMax) chosen.add(p.uid)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = accent),
                                )
                            } else if (p.online) {
                                Box(
                                    Modifier.size(9.dp).clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                )
                            }
                        }
                        if (picking) return@Column
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionChip(Icons.Rounded.Payments, "Pay", accent, Modifier.weight(1f)) {
                                onPay(p)
                            }
                            ActionChip(
                                Icons.AutoMirrored.Rounded.Chat, "Message", accent, Modifier.weight(1f),
                            ) { onMessage(p) }
                            // Voice call over the internet — no phone number
                            // needed, and no carrier minutes used.
                            ActionChip(
                                Icons.Rounded.Call, "Call", accent, Modifier.weight(1f),
                            ) { onCall(p) }
                            ActionChip(
                                Icons.Rounded.Videocam, "Video", accent, Modifier.weight(1f),
                            ) { onVideoCall(p) }
                        }
                        if (p.phone.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Dial ${p.phone} instead",
                                fontSize = 10.sp,
                                color = accent.copy(alpha = 0.8f),
                                modifier = Modifier.clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_DIAL, "tel:${p.phone}".toUri())
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Placing the group call. Kept out of the list so the choice stays
        // visible while scrolling to find the last person.
        if (picking) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionChip(
                        Icons.Rounded.Call,
                        if (chosen.isEmpty()) "Call" else "Call ${chosen.size}",
                        accent, Modifier.weight(1f), enabled = chosen.isNotEmpty(),
                    ) {
                        onGroupCall(people.filter { it.uid in chosen }, false)
                        picking = false
                        chosen.clear()
                    }
                    ActionChip(
                        Icons.Rounded.Videocam,
                        if (chosen.isEmpty()) "Video" else "Video ${chosen.size}",
                        accent, Modifier.weight(1f), enabled = chosen.isNotEmpty(),
                    ) {
                        onGroupCall(people.filter { it.uid in chosen }, true)
                        picking = false
                        chosen.clear()
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (enabled) accent.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
        border = BorderStroke(
            1.dp,
            if (enabled) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f),
        ),
        modifier = modifier.clickable(enabled = enabled) { onClick() },
    ) {
        Row(
            Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, null, modifier = Modifier.size(15.dp),
                tint = if (enabled) accent
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label, fontSize = 11.sp,
                color = if (enabled) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
        }
    }
}
