package com.axzora.os

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.chat.DmClient
import com.axzora.os.chat.DmMessage
import com.axzora.os.chat.DmThread
import com.axzora.os.data.UserSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Chats hub: the family group chat plus every private conversation.
 * Private threads only ever exist on the two phones involved.
 */
@Composable
fun ConversationsScreen(
    settings: UserSettings,
    myUid: String,
    onBack: () -> Unit,
    onOpenFamily: () -> Unit,
    onOpenDm: (uid: String, name: String) -> Unit,
    onPickPerson: () -> Unit,
) {
    val client = remember { DmClient() }
    var threads by remember { mutableStateOf<List<DmThread>>(emptyList()) }
    val server = settings.serverUrl.ifBlank { Config.DEFAULT_SERVER }

    LaunchedEffect(myUid) {
        while (true) {
            if (myUid.isNotBlank()) threads = client.threads(server, myUid)
            delay(4000)
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        PayChatTopRow("Chats", onBack)

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
            // Family group chat — everyone with the family code.
            item {
                ThreadRow(
                    icon = { CircleBadge { Icon(Icons.Rounded.Groups, null, tint = Color.White) } },
                    title = "Family group",
                    sub = if (settings.familyCode.isBlank()) "Set a family code in Settings to use the group"
                          else "Everyone with your family code sees this",
                    unread = 0,
                    onClick = onOpenFamily,
                )
            }
            item {
                Row(
                    Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("PRIVATE — only you and them", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        fontWeight = FontWeight.Bold)
                }
            }
            items(threads, key = { it.peerUid }) { t ->
                ThreadRow(
                    icon = {
                        CircleBadge {
                            Text(t.peerName.trim().take(1).uppercase().ifBlank { "?" },
                                color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    title = t.peerName,
                    sub = lastSeenText(t.lastTs),
                    unread = t.unread,
                    onClick = { onOpenDm(t.peerUid, t.peerName) },
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onPickPerson() },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("New private chat — pick a person", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleBadge(content: @Composable () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ThreadRow(
    icon: @Composable () -> Unit,
    title: String,
    sub: String,
    unread: Int,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(sub, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            }
            if (unread > 0) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$unread", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

private fun lastSeenText(ts: Long): String = if (ts <= 0) "" else try {
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ts))
} catch (e: Exception) { "" }

/** One private conversation. Polls while open; marks itself read. */
@Composable
fun DmScreen(
    settings: UserSettings,
    myUid: String,
    peerUid: String,
    peerName: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = remember { DmClient() }
    val messages = remember { mutableStateListOf<DmMessage>() }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val server = settings.serverUrl.ifBlank { Config.DEFAULT_SERVER }
    val myName = settings.displayName.ifBlank { "Me" }

    LaunchedEffect(peerUid) {
        while (true) {
            val since = messages.lastOrNull()?.id ?: 0
            val fresh = client.poll(server, myUid, peerUid, since)
            if (fresh.isNotEmpty()) {
                messages.addAll(fresh)
                listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
                client.markRead(server, myUid, peerUid, messages.last().id)
            }
            delay(2500)
        }
    }

    fun sendNow() {
        val text = input.trim()
        if (text.isBlank()) return
        input = ""
        scope.launch {
            val ok = client.send(server, myUid, myName, peerUid, text)
            if (!ok) status = "Couldn't reach the server — message not sent."
            else status = ""
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 48.dp).imePadding()) {
        PayChatTopRow(peerName, onBack)
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Private — only you and $peerName can read this.",
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
                        Text(
                            m.text,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (m.mine) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Message $peerName…") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendNow() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { sendNow() }) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
            }
        }
    }
}
