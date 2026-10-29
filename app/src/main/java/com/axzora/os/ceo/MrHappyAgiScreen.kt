package com.axzora.os.ceo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

/**
 * MR HAPPY AGI CORE — the "brain" screen.
 *
 * A living overview of Mr Happy's intelligence modules, agents, and the world
 * simulation, plus a live orbit animation around the core. This is the
 * "AI-native OS" centre of the interface: the assistant isn't a chat screen, it's
 * the intelligence layer you look at.
 */
@Composable
fun MrHappyAgiScreen(
    ceo: CeoAgent,
    onOpenMrHappy: () -> Unit,
    onNavigate: (com.axzora.os.Screen) -> Unit,
    onBack: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().padding(top = 40.dp)
            .padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦ MR HAPPY AI", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = accent)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Text("Your AI assistant core — a smart shortcut layer that helps you use the phone.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        Spacer(Modifier.height(14.dp))

        // ── the AI orb / core ──
        AiOrb(accent)
        Spacer(Modifier.height(6.dp))
        Text("● ASSISTANT", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFF7CFC9C), modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("online · asks before it acts", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Spacer(Modifier.height(16.dp))

        // ── intelligence modules (the "brain map"), honestly labelled ──
        // Only the ones whose code genuinely runs are "Active"; the rest are
        // "Planned" — nothing on a phone really runs an autonomous AGI.
        Text("ASSISTANT MODULES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Reasoning Engine" to "Active",
                "Voice Intelligence" to "Active",
                "Memory Engine" to "Active",
                "Knowledge Graph" to "Active",
                "Agent Network" to "Active",
                "Planning Engine" to "Planned",
                "Automation Engine" to "Planned",
                "Security Intelligence" to "Planned",
                "Business Intelligence" to "Planned",
            ).forEach { (module, status) ->
                ModuleRow(module, status, accent)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── the agency ──
        Text("THE AGENCY (${ceo.agents.size} agents)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(6.dp))
        ceo.agents.forEach { agent ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(agent.emoji, fontSize = 17.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(agent.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${ceo.usageOf(agent.id)} ×", fontSize = 12.sp, color = accent)
                    Spacer(Modifier.width(8.dp))
                    Text("●", fontSize = 11.sp, color = Color(0xFF7CFC9C))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── world sim ──
        ceo.world?.let { w ->
            val worldTick = remember { mutableIntStateOf(0) }
            Text("WORLD SIMULATION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(6.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))) {
                Text(
                    WorldEngine.map(w),
                    fontSize = 10.sp, lineHeight = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                ceo.world?.let { WorldEngine.step(it, 1) }
                worldTick.intValue++  // force recompose on the map
            }, modifier = Modifier.fillMaxWidth()) { Text("Step world +1 day") }
        }

        Spacer(Modifier.height(16.dp))

        // ── talk to the core ──
        Button(onClick = onOpenMrHappy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("🎙 Talk to Mr Happy")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onNavigate(com.axzora.os.Screen.CEO) },
            modifier = Modifier.fillMaxWidth()) { Text("Open CEO Command Center") }

        Spacer(Modifier.height(24.dp))
    }
}

/** The animated, pulsing core orb. */
@Composable
private fun AiOrb(accent: Color) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        0.6f, 1.0f,
        infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val rotate by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(12000, easing = FastOutSlowInEasing)),
        label = "rotate",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(Modifier.size(140.dp)) {
            val c = center
            val r = size.minDimension / 2f
            // outer glow rings
            for (ring in 0..2) {
                drawCircle(
                    color = accent.copy(alpha = 0.08f * (ring + 1)),
                    radius = r * (0.9f - ring * 0.12f) * pulse,
                    center = c,
                )
            }
            // rotating highlight arc
            drawArc(
                color = accent.copy(alpha = 0.6f),
                startAngle = rotate,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(c.x - r * 0.7f, c.y - r * 0.7f),
                size = androidx.compose.ui.geometry.Size(r * 1.4f, r * 1.4f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.08f),
            )
            // core
            drawCircle(
                color = accent.copy(alpha = 0.9f),
                radius = r * 0.42f * pulse,
                center = c,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.8f),
                radius = r * 0.16f * pulse,
                center = c,
            )
        }
    }
}

@Composable
private fun ModuleRow(module: String, status: String, accent: Color) {
    val active = status == "Active"
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (active) "✓" else "•", fontSize = 12.sp,
                color = if (active) Color(0xFF7CFC9C) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            Spacer(Modifier.width(10.dp))
            Text(module, fontSize = 13.sp, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground)
            Text(status.uppercase(), fontSize = 10.sp,
                color = if (active) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
        }
    }
}
