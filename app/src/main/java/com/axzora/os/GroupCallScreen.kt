package com.axzora.os

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.call.AudioHealth
import com.axzora.os.call.GroupInfo
import com.axzora.os.call.GroupPerson
import com.axzora.os.call.Room
import com.axzora.os.call.VideoView
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * A group call: one tile per person, laid out to fill whatever screen it is on.
 *
 * Each connection in a group comes up independently, so the tiles are not all
 * live at the same moment — a tile says what its own connection is doing
 * rather than borrowing one status for the whole call.
 */
@Composable
fun GroupCallScreen(
    info: GroupInfo,
    accent: Color,
    eglBase: EglBase,
    localVideo: VideoTrack?,
    onLeave: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onAddPeople: () -> Unit = {},
    canAddMore: Boolean = true,
    health: AudioHealth = AudioHealth(),
) {
    // Leaving a group call is a deliberate tap, never a stray edge swipe.
    BackHandler(enabled = true) { /* stay in the call */ }

    var elapsed by remember(info.startedAt) { mutableStateOf(0L) }
    LaunchedEffect(info.startedAt) {
        if (info.startedAt > 0) {
            while (true) {
                elapsed = (System.currentTimeMillis() - info.startedAt) / 1000
                delay(1000)
            }
        }
    }

    val landscape = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val people = info.people
    // Two columns is the most a phone can show without faces becoming stamps;
    // turned sideways there is width for two even with one person.
    val columns = when {
        people.size <= 1 -> 1
        landscape -> 2
        people.size == 2 -> 1
        else -> 2
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07090F))) {

        Column(Modifier.fillMaxSize()) {

            // ---- who is here, and for how long ----
            Column(
                Modifier.fillMaxWidth().padding(top = 46.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    info.title,
                    fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                val live = people.count { it.connected }
                val label = when {
                    // Shown after the call is over so its reason can be read;
                    // "Ringing 0…" would be a lie at that point.
                    !info.active -> "Call ended"
                    info.startedAt > 0 ->
                        "%d:%02d · %d in the call".format(elapsed / 60, elapsed % 60, live + 1)
                    else -> "Ringing ${people.size}…"
                }
                StatusPill(
                    text = label,
                    color = when {
                        !info.active -> Color.White.copy(alpha = 0.25f)
                        info.startedAt > 0 -> Color(0xFF22C55E)
                        else -> accent
                    },
                    pulsing = info.active && info.startedAt == 0L,
                )
                if (info.error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        info.error, fontSize = 13.sp, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // One microphone feeds every leg, so "they can't hear me" in a
                // group is one fault, not one per person — and worth saying
                // once, here, rather than on each tile.
                if (info.startedAt > 0 && health.warning.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        health.warning, fontSize = 13.sp, textAlign = TextAlign.Center,
                        color = Color(0xFFFCA5A5),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        health.summary, fontSize = 11.sp, textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- the tiles ----
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                people.chunked(columns).forEach { row ->
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { person ->
                            PersonTile(
                                person = person,
                                accent = accent,
                                eglBase = eglBase,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        // A half-empty last row must not stretch its tiles to
                        // twice the size of every other one.
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // ---- controls ----
            Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GroupButton(
                        if (info.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        if (info.muted) "Unmute" else "Mute",
                        if (info.muted) accent else Color.White.copy(alpha = 0.18f),
                        onToggleMute,
                    )
                    GroupButton(
                        if (info.video) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                        if (info.video) "Video on" else "Video",
                        if (info.video) accent else Color.White.copy(alpha = 0.18f),
                        onToggleVideo,
                    )
                    if (info.video) {
                        GroupButton(
                            Icons.Rounded.Cameraswitch, "Flip",
                            Color.White.copy(alpha = 0.18f), onSwitchCamera,
                        )
                    }
                    GroupButton(
                        Icons.Rounded.VolumeUp, "Speaker",
                        if (info.speaker) accent else Color.White.copy(alpha = 0.18f),
                        onToggleSpeaker,
                    )
                    // Add someone without anyone hanging up — the call keeps
                    // running while the new guest is rung.
                    if (canAddMore) {
                        GroupButton(
                            Icons.Rounded.PersonAdd, "Add",
                            Color.White.copy(alpha = 0.18f), onAddPeople,
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                GroupButton(
                    Icons.Rounded.CallEnd, "Leave", Color(0xFFEF4444), onLeave, big = true,
                )
            }
        }

        // Self-view, out of the way of both the header and the controls.
        if (info.video && localVideo != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 14.dp)
                    .size(width = 86.dp, height = 118.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            ) {
                VideoView(
                    track = localVideo,
                    eglBase = eglBase,
                    mirror = info.frontCamera,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PersonTile(
    person: GroupPerson,
    accent: Color,
    eglBase: EglBase,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val track = person.video
        if (person.hasVideo && track != null) {
            // A tile is roughly the shape of a phone camera, so filling it
            // crops very little and wastes no space on black bars.
            VideoView(track = track, eglBase = eglBase, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(
                        Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.5f)))
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        person.name.trim().take(1).uppercase().ifBlank { "?" },
                        fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    person.name.ifBlank { person.uid },
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Name plate over video, and the per-person connection state. Someone
        // on a slow network is still joining while the rest are talking.
        Row(
            Modifier.align(Alignment.BottomStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (person.hasVideo && person.video != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Text(
                        person.name.ifBlank { person.uid },
                        fontSize = 11.sp, color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else if (!person.connected) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Text(
                        "Connecting…", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Someone has asked this phone into a group call.
 *
 * Not a ringing call — nobody has dialled yet. Accepting is what makes this
 * phone dial everyone already inside.
 */
@Composable
fun GroupInviteSheet(
    room: Room,
    accent: Color,
    onJoin: (video: Boolean) -> Unit,
    onDecline: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF07090F).copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val pulse by rememberInfiniteTransition(label = "groupring").animateFloat(
                0.5f, 1f,
                infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "alpha",
            )
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(accent.copy(alpha = pulse * 0.8f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Call, null, tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                room.hostName.ifBlank { room.host },
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "is starting ${room.title.lowercase()}",
                fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f),
            )
            if (room.members.size > 1) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${room.members.size} already in",
                    fontSize = 13.sp, color = accent,
                )
            }
            Spacer(Modifier.height(38.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                GroupButton(
                    Icons.Rounded.Call, "Join", Color(0xFF22C55E),
                    { onJoin(false) }, big = true,
                )
                GroupButton(
                    Icons.Rounded.Videocam, "Video", Color(0xFF3B82F6),
                    { onJoin(true) }, big = true,
                )
                GroupButton(
                    Icons.Rounded.CallEnd, "Decline", Color(0xFFEF4444),
                    onDecline, big = true,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, pulsing: Boolean) {
    val breathe by rememberInfiniteTransition(label = "pill").animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.18f)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pulsing) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(color.copy(alpha = breathe))
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

@Composable
private fun GroupButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
    big: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (big) 66.dp else 52.dp).clip(CircleShape)
                .background(background).clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(if (big) 28.dp else 22.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
    }
}


/**
 * Picks more people to pull into a call already running.
 *
 * Deliberately a sheet over the call rather than a screen change: the call
 * stays visible and audible the whole time, which is the difference between
 * adding someone and interrupting everyone.
 */
@Composable
fun AddPeopleSheet(
    people: List<com.axzora.os.wallet.Person>,
    alreadyIn: Set<String>,
    remainingSlots: Int,
    accent: Color,
    onAdd: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val chosen = remember { mutableStateListOf<String>() }
    val available = people.filter { it.uid !in alreadyIn }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Add to the call",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (remainingSlots > 0)
                        "Room for " + remainingSlots + " more. Nobody has to hang up."
                    else "This call is full.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(14.dp))

                if (available.isEmpty()) {
                    Text(
                        "Nobody else to add yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(available, key = { it.uid }) { person ->
                            val on = person.uid in chosen
                            val full = chosen.size >= remainingSlots && !on
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !full) {
                                        if (on) chosen.remove(person.uid)
                                        else chosen.add(person.uid)
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    person.name,
                                    Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (full) 0.35f else 1f,
                                    ),
                                )
                                if (on) {
                                    Icon(Icons.Rounded.CheckCircle, null, tint = accent)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onAdd(chosen.toList()) },
                        enabled = chosen.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (chosen.isEmpty()) "Add" else "Add " + chosen.size)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
