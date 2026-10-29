package com.axzora.os.ceo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The Digital CEO Control Center — a live dashboard for Mr Happy as CEO.
 *
 * Shows the executive state: agents on the team, tasks dispatched/running, the
 * current world simulation, and a voice-command box. It's a real, working UI that
 * drives [CeoAgent] on-device.
 */
@Composable
fun CeoDashboard(
    ceo: CeoAgent,
    onVoice: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val feed = remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(top = 40.dp)
            .padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🧠 MR HAPPY CEO", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Text("Your AI CEO on this phone. Delegates to specialists, builds worlds, and reports by voice.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        Spacer(Modifier.height(14.dp))

        // ── Executive stats ──
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("EXECUTIVE STATS", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                StatRow("Active Agents", "${ceo.agents.size}/${ceo.agents.size}")
                StatRow("Tasks Dispatched", "${ceo.tasksDispatched}")
                StatRow("Tasks Running", "${ceo.activeTasks.size}")
                StatRow("World", ceo.worldSummary())
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── The team ──
        Text("THE TEAM", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        ceo.agents.forEach { agent ->
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(agent.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(agent.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        val done = agent.memory.size
                        Text("tasks done: $done · goal: ${agent.goal.ifBlank { "—" }}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                    Text("${ceo.usageOf(agent.id)} ×", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── World map (if a world exists) ──
        ceo.world?.let { w ->
            Text("WORLD MAP", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))) {
                Text(
                    WorldEngine.map(w),
                    fontSize = 11.sp, lineHeight = 15.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── Voice command box ──
        Text("VOICE COMMAND", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("e.g. \"create a cafe marketing campaign\" or \"build a smart city\"") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        scope.launch {
                            feed.value = listOf(ceo.run(input)) + feed.value.take(8)
                        }
                        input = ""
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Run") }
            OutlinedButton(onClick = { onVoice("") }) { Text("🎙 Voice") }
        }

        Spacer(Modifier.height(14.dp))

        // ── Output feed ──
        if (feed.value.isNotEmpty()) {
            Text("OUTPUT", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            feed.value.forEach { block ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))) {
                    Text(block, fontSize = 12.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
