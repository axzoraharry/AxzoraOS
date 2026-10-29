package com.axzora.os.soundmesh

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread

/**
 * SoundMesh — send encrypted text through sound, phone to phone, with no
 * internet. Speaker plays the 2-FSK burst; microphone records and decodes it.
 */
@Composable
fun SoundMeshScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready. Type a message, then Send.") }
    var received by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val hasMic = remember { mutableStateOf(
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    ) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMic.value = it
    }

    fun send() {
        if (message.isBlank()) { status = "Type a message first."; return }
        busy = true
        status = "Sending… (hold speaker near the other phone)"
        thread {
            runCatching {
                val pcm = SoundMesh.encodeToPcm(message)
                val track = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SoundMesh.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    pcm.size * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManagerCompat.audioSession(),
                )
                track.write(pcm, 0, pcm.size)
                track.play()
                while (track.playState == AudioTrack.PLAYSTATE_PLAYING) { Thread.sleep(20) }
                track.release()
            }.onSuccess { status = "Sent ✓" } .onFailure { status = "Couldn't play: ${it.message}" }
            busy = false
        }
    }

    fun listen() {
        if (!hasMic.value) {
            perm.launch(Manifest.permission.RECORD_AUDIO)
            if (!hasMic.value) { status = "Need the Microphone permission to listen."; return }
        }
        busy = true
        status = "Listening for ~18s… hold the phones a few cm apart"
        thread {
            runCatching {
                val minBuf = AudioRecord.getMinBufferSize(
                    SoundMesh.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(8000)
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SoundMesh.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                )
                rec.startRecording()
                val buf = ShortArray(SoundMesh.SAMPLE_RATE * 19) // ~19s
                var read = 0
                while (read < buf.size) {
                    val n = rec.read(buf, read, buf.size - read)
                    if (n > 0) read += n else break
                }
                rec.stop(); rec.release()
                val decoded = SoundMesh.decodeFromPcm(buf.copyOf(read))
                if (decoded != null) {
                    received = decoded
                    status = "Decoded ✓"
                } else {
                    status = "No message decoded. Try again closer / quieter."
                }
            }.onFailure { status = "Couldn't listen: ${it.message}" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 44.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground) }
            Column(Modifier.weight(1f)) {
                Text("SoundMesh", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text("Encrypted text through sound · no internet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            }
        }

        Column(Modifier.padding(20.dp)) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { send() }, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Send (speaker)")
                }
                OutlinedButton(onClick = { listen() }, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Listen (mic)")
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(status, fontSize = 13.sp, color = accent)
            if (received.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Received", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer { Text(received, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "How it works: your message is AES-256 encrypted, turned into a burst " +
                    "of two tones (1050/1500 Hz), played through the speaker; the other " +
                    "phone records it and decodes back. Keep the phones a few cm apart " +
                    "and off loudspeaker for best results. This is the core primitive — " +
                    "file/voice/ultrasonic/mesh are the next layers.",
                fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}

/** Tiny wrapper so I only reference android.media.* once here. */
private object AudioManagerCompat {
    fun audioSession(): Int = 0
}
