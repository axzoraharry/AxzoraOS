package com.axzora.os

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.call.AudioHealth
import com.axzora.os.call.CallInfo
import com.axzora.os.call.CallState
import com.axzora.os.call.VideoView
import com.axzora.os.ui.AuroraBackground
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlinx.coroutines.delay

/**
 * Full-screen call UI. Shown over everything else whenever a call is live,
 * ringing, or being placed.
 */
@Composable
fun CallScreen(
    info: CallInfo,
    accent: Color,
    eglBase: EglBase,
    localVideo: VideoTrack?,
    remoteVideo: VideoTrack?,
    onAccept: (video: Boolean) -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    health: AudioHealth = AudioHealth(),
    pip: Boolean = false,
) {
    var elapsed by remember(info.startedAt) { mutableStateOf(0L) }
    LaunchedEffect(info.startedAt, info.state) {
        if (info.state == CallState.ACTIVE && info.startedAt > 0) {
            while (true) {
                elapsed = (System.currentTimeMillis() - info.startedAt) / 1000
                delay(1000)
            }
        }
    }

    val ringing = info.state == CallState.RINGING ||
        info.state == CallState.DIALLING ||
        info.state == CallState.CONNECTING

    /*
     * Swallow the back gesture.
     *
     * The call screen returns before the app's own back handler is registered,
     * so back fell through to Android and finished the Activity — an accidental
     * edge swipe made the call disappear mid-conversation. Hanging up has to be
     * a deliberate tap on the red button.
     */
    androidx.activity.compose.BackHandler(enabled = true) { /* stay in the call */ }

    // A live video call shows the remote feed full-bleed; everything else keeps
    // the avatar layout.
    val showVideo = info.state == CallState.ACTIVE && (info.remoteVideo || info.video)

    if (pip) {
        /*
         * The floating tile.
         *
         * At this size every control would sit on top of the person's face,
         * and taps in a PiP window go to the system anyway — it expands back
         * to full screen rather than reaching the buttons. So show the one
         * thing the tile exists for: the other person. Filling is right here
         * because the tile already matches the video's shape.
         */
        Box(Modifier.fillMaxSize().background(Color(0xFF07090F))) {
            if (remoteVideo != null) {
                VideoView(
                    track = remoteVideo,
                    eglBase = eglBase,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (localVideo != null) {
                VideoView(
                    track = localVideo,
                    eglBase = eglBase,
                    mirror = info.frontCamera,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07090F))) {

        if (showVideo && remoteVideo != null) {
            VideoView(
                track = remoteVideo,
                eglBase = eglBase,
                // Show the whole of the other person rather than cropping
                // them to the shape of this phone's screen.
                fill = false,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AuroraBackground(accent = accent, dark = true) {}
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(60.dp))

            // Identity block. Compact once video is carrying the screen.
            if (!showVideo) {
                Box(contentAlignment = Alignment.Center) {
                    if (ringing) {
                        val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
                            1f, 1.6f,
                            infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
                            label = "pulse",
                        )
                        Canvas(Modifier.size(190.dp)) {
                            drawCircle(
                                color = accent.copy(alpha = (1.6f - pulse).coerceIn(0f, 0.5f)),
                                radius = size.minDimension / 2 * pulse,
                            )
                        }
                    }
                    Box(
                        Modifier.size(130.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            info.peerName.trim().take(1).uppercase().ifBlank { "?" },
                            fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }

            Text(
                info.peerName.ifBlank { info.peerUid },
                fontSize = if (showVideo) 20.sp else 30.sp,
                fontWeight = FontWeight.Bold, color = Color.White,
            )
            Spacer(Modifier.height(12.dp))

            // Call state as a filled pill rather than a faint line of text —
            // "is it actually ringing?" should never be a question.
            val (label, pillColor) = when (info.state) {
                CallState.DIALLING -> "Calling…" to accent
                CallState.RINGING ->
                    (if (info.incoming) {
                        if (info.video) "Incoming video call" else "Incoming call"
                    } else "Ringing…") to accent
                CallState.CONNECTING -> "Connecting…" to accent
                CallState.ACTIVE ->
                    ("%d:%02d".format(elapsed / 60, elapsed % 60) + (if (info.relay) "  ·  via relay" else "")) to Color(0xFF22C55E)
                CallState.ENDED -> "Call ended" to Color.White.copy(alpha = 0.25f)
                CallState.IDLE -> "" to Color.Transparent
            }

            if (label.isNotBlank()) {
                val breathe by rememberInfiniteTransition(label = "state").animateFloat(
                    0.55f, 1f,
                    infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                    label = "alpha",
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = pillColor.copy(alpha = if (ringing) 0.20f else 0.16f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ringing) {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape)
                                    .background(pillColor.copy(alpha = breathe))
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (info.state == CallState.ENDED)
                                Color.White.copy(alpha = 0.7f) else pillColor,
                        )
                    }
                }
            }
            if (info.error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    info.error, fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            /*
             * What the media counters say, when they disagree with the call
             * looking healthy.
             *
             * "The other person can't hear me" is the one fault a call screen
             * cannot show by itself — the phone showing it is the phone that
             * is working. This says which direction is dead, and carries the
             * one line worth quoting when reporting it.
             */
            if (info.state == CallState.ACTIVE && health.warning.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.16f),
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            health.warning, fontSize = 13.sp, textAlign = TextAlign.Center,
                            color = Color(0xFFFCA5A5),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            health.summary, fontSize = 11.sp, textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.55f),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (info.state == CallState.ACTIVE) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CallButton(
                        if (info.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        if (info.muted) "Unmute" else "Mute",
                        if (info.muted) accent else Color.White.copy(alpha = 0.18f),
                        onToggleMute,
                    )
                    if (!info.relay) CallButton(
                        if (info.video) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                        if (info.video) "Video on" else "Video",
                        if (info.video) accent else Color.White.copy(alpha = 0.18f),
                        onToggleVideo,
                    )
                    if (info.video) {
                        CallButton(
                            Icons.Rounded.Cameraswitch, "Flip",
                            Color.White.copy(alpha = 0.18f), onSwitchCamera,
                        )
                    }
                    CallButton(
                        Icons.Rounded.VolumeUp, "Speaker",
                        if (info.speaker) accent else Color.White.copy(alpha = 0.18f),
                        onToggleSpeaker,
                    )
                }
                Spacer(Modifier.height(26.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                if (info.incoming && info.state == CallState.RINGING) {
                    // Answering with or without camera is the caller's choice
                    // to offer and the receiver's to make.
                    CallButton(
                        Icons.Rounded.Call, "Audio", Color(0xFF22C55E),
                        { onAccept(false) }, big = true,
                    )
                    CallButton(
                        Icons.Rounded.Videocam, "Video", Color(0xFF3B82F6),
                        { onAccept(true) }, big = true,
                    )
                }
                CallButton(
                    Icons.Rounded.CallEnd,
                    if (info.state == CallState.ACTIVE) "End" else "Decline",
                    Color(0xFFEF4444), onHangUp, big = true,
                )
            }

            Spacer(Modifier.height(40.dp))
        }

        // Self-view, tucked out of the way of the controls.
        if (info.video && localVideo != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 18.dp)
                    .size(width = 108.dp, height = 152.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
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
private fun CallButton(
    icon: ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
    big: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (big) 68.dp else 56.dp).clip(CircleShape)
                .background(background).clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(if (big) 30.dp else 24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
    }
}
