package com.axzora.os.assistant

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.axzora.os.ui.AxzoraTheme

private val Ember = Color(0xFFF0A73C)

/** The draggable button that sits over other apps. */
@Composable
fun FloatingBubble(onTap: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "bubble").animateFloat(
        1f, 1.10f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(
        Modifier
            .size(58.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Ember, Color(0xFFD9822B))))
            .border(2.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .clickable { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.AutoAwesome, "Axzora assistant",
            tint = Color(0xFF221606), modifier = Modifier.size(26.dp),
        )
    }
}

private data class Line(val text: String, val mine: Boolean)

/**
 * The panel that opens from the bubble.
 *
 * Deliberately small: it sits over whatever the user was already doing, so it
 * takes the bottom of the screen rather than covering the whole thing.
 */
@Composable
fun FloatingPanel(
    engine: CommandEngine,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current
    val voice = remember { VoiceController(context) }
    val lines = remember {
        mutableStateListOf(Line("Ask me anything, or say what you want to do.", false))
    }
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { voice.stop() } }

    fun ask(text: String) {
        if (text.isBlank() || thinking) return
        lines.add(Line(text, true))
        scope.launch {
            thinking = true
            val history = lines.dropLast(1).map { Brain.Turn(it.text, it.mine) }
            val r = engine.ask(text, history)
            thinking = false
            lines.add(Line(r.reply, false))
            // Anything that wants a full screen hands off to the app itself.
            if (r.navigateTo != null) onOpenApp()
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    AxzoraTheme(darkTheme = true, accent = Ember) {
        Surface(
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, Ember.copy(alpha = 0.35f),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp).imePadding()) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Ember, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Axzora", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Rounded.Close, "Close",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { onClose() },
                    )
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 260.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(lines) { line ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (line.mine) Arrangement.End else Arrangement.Start,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (line.mine) Ember
                                else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.widthIn(max = 280.dp),
                            ) {
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (line.mine) Color(0xFF221606)
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = {
                            Text(
                                when {
                                    thinking -> "Thinking…"
                                    listening -> "Listening…"
                                    else -> "Type or tap the mic"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask(input.trim()); input = "" }),
                    )
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(
                        onClick = {
                            voice.start(
                                onPartial = { input = it },
                                onResult = { spoken -> input = ""; ask(spoken) },
                                onState = { listening = it },
                                onError = { lines.add(Line(it, false)) },
                            )
                        },
                    ) { Icon(Icons.Rounded.Mic, "Speak") }
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(onClick = { ask(input.trim()); input = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                    }
                }
            }
        }
    }
}
