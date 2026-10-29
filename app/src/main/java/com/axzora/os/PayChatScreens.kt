package com.axzora.os

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.chat.ChatClient
import com.axzora.os.chat.ChatMessage
import com.axzora.os.data.UserSettings
import com.axzora.os.pay.PayEntry
import com.axzora.os.pay.PayManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* ----------------------------- AXZORA PAY ----------------------------- */

@Composable
fun PayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pay = remember { PayManager(context) }
    val history by pay.history.collectAsState(initial = emptyList())

    var vpa by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        PayChatTopRow("Axzora Pay", onBack)
        Column(Modifier.padding(horizontal = 20.dp).weight(1f)) {

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Payment completes in your UPI app (GPay, PhonePe…). " +
                            "Axzora never sees your PIN or balance.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = vpa, onValueChange = { vpa = it },
                label = { Text("UPI ID (e.g. name@upi)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("₹ Amount") },
                    singleLine = true, modifier = Modifier.width(130.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    if (vpa.isBlank()) { status = "Enter a UPI ID."; return@Button }
                    val ok = pay.launchUpi(vpa, name, amount, note)
                    if (ok) {
                        status = "Confirm in your UPI app."
                        scope.launch {
                            pay.record(PayEntry(vpa, name, amount, note, System.currentTimeMillis()))
                        }
                    } else {
                        status = "No UPI app found on this device."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.AccountBalanceWallet, null)
                Spacer(Modifier.width(8.dp))
                Text("Pay${if (amount.isNotBlank()) " ₹$amount" else ""}", fontSize = 16.sp)
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }

            if (history.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Recent", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(history) { e ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name.ifBlank { e.vpa }, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground)
                                    Text(e.vpa + timeSuffix(e.ts), fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                }
                                if (e.amount.isNotBlank()) {
                                    Text("₹${e.amount}", fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun timeSuffix(ts: Long): String = try {
    " · " + DateTimeFormatter.ofPattern("d MMM, h:mm a")
        .format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
} catch (e: Exception) { "" }

/* ----------------------------- PRIVATE MESSAGES ----------------------------- */

@Composable
fun MessagesScreen(settings: UserSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { ChatClient() }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val ready = settings.serverUrl.isNotBlank() && settings.familyCode.isNotBlank()
    val myName = settings.displayName.ifBlank { "Me" }

    // Poll the relay every 3 seconds while this screen is open.
    LaunchedEffect(ready) {
        if (!ready) {
            status = "Set Server URL and Family code in Settings first."
            return@LaunchedEffect
        }
        status = ""
        while (true) {
            val since = messages.lastOrNull()?.id ?: 0
            val fresh = client.poll(settings.serverUrl, settings.familyCode, since, myName)
            if (fresh.isNotEmpty()) {
                messages.addAll(fresh)
                listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
            }
            delay(3000)
        }
    }

    fun sendNow() {
        val text = input.trim()
        if (text.isBlank() || !ready) return
        input = ""
        scope.launch {
            val ok = client.send(settings.serverUrl, settings.familyCode, myName, text)
            if (!ok) status = "Couldn't reach the server — message not sent."
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        PayChatTopRow("Messages", onBack)
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "End-to-end encrypted with your family code. The server sees only scrambled text.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        color = if (m.mine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.widthIn(max = 290.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (!m.mine) {
                                Text(m.from, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                m.text,
                                color = if (m.mine) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Message your family…") },
                modifier = Modifier.weight(1f),
                enabled = ready,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendNow() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { sendNow() }, enabled = ready) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
            }
        }
    }
}

/* shared header for these two screens */
@Composable
internal fun PayChatTopRow(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(title, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}
