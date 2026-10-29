package com.axzora.os

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.wallet.AdminNotify
import com.axzora.os.wallet.AdminOverview
import com.axzora.os.wallet.ClaimResult
import com.axzora.os.wallet.AdminUser
import com.axzora.os.wallet.Currencies
import com.axzora.os.wallet.Currency
import com.axzora.os.wallet.LedgerClient
import com.axzora.os.wallet.LedgerResult
import com.axzora.os.wallet.NotifyResult
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private enum class AdminTab { PEOPLE, NOTIFY, MINT, AI, RELAY }

/**
 * The admin console, kept off the wallet entirely.
 *
 * Operator tools sitting inside a screen every user opens made the wallet
 * cramped and put controls in front of people who can never use them. This
 * screen only exists once the code is accepted, and the server re-checks that
 * code on every action it performs.
 */
@Composable
fun AdminScreen(
    serverUrl: String,
    currencyCode: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallet = remember { WalletStore(context) }
    val settingsStore = remember { com.axzora.os.data.SettingsStore(context) }
    val adminSettings by settingsStore.settings
        .collectAsState(initial = com.axzora.os.data.UserSettings())
    val adminAgentUrl = adminSettings.adminAgentUrl
    val notify = remember(serverUrl) { AdminNotify { serverUrl } }
    val ledger = remember(serverUrl) { LedgerClient { serverUrl } }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = MaterialTheme.colorScheme.primary
    val currency = remember(currencyCode) {
        if (currencyCode.isBlank()) Currencies.forLocale() else Currencies.byCode(currencyCode)
    }

    var code by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf<AdminOverview?>(null) }
    var tab by remember { mutableStateOf(AdminTab.PEOPLE) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var deviceId by remember { mutableStateOf("") }

    /*
     * Which hardware this console belongs to. The admin code alone is a shared
     * secret: a leaked code, or a backup of this phone's app data restored onto
     * another handset, used to arrive at a fully unlocked console. The guard
     * seals the code to this device's Keystore (non-exportable) and to a hash
     * of its identifiers, so the seal opens here and nowhere else.
     */
    val guard = remember { com.axzora.os.security.AdminDeviceGuard(context) }
    var guardState by remember { mutableStateOf(com.axzora.os.security.AdminDeviceGuard.State.UNBOUND) }
    var guardNote by remember { mutableStateOf("") }

    suspend fun refresh() {
        overview = notify.overview(code, deviceId)
    }

    /*
     * Re-open without re-typing, but only for a device that genuinely holds
     * the console.
     *
     * This used to read a code that fell back to a constant compiled into the
     * app, so every install arrived here with a working code and unlocked
     * itself. Now a blank stored code means the gate stays shut, and even a
     * stored code only opens the screen if the server still recognises this
     * device as the holder.
     */
    LaunchedEffect(Unit) {
        deviceId = wallet.ensureUid()

        /*
         * Hardware first, code second.
         *
         * The seal is the authority on *where* the console may open; the server
         * remains the authority on *whether* the code is still valid. A seal
         * that belongs to other hardware is refused outright and wiped, so a
         * restored backup cannot re-home the console. A seal that cannot be
         * opened at all (key cleared, lock screen changed) is reported as such
         * and the operator retypes the code — fail closed, but recoverable.
         */
        val status = guard.status()
        guardState = status.state
        guardNote = status.message
        val stored = if (status.state == com.axzora.os.security.AdminDeviceGuard.State.BOUND_HERE) {
            status.code.orEmpty()
        } else {
            // Do not fall back to the plaintext copy on hardware we refuse:
            // that copy is exactly the thing a backup would carry.
            wallet.clearAdmin()
            ""
        }

        if (stored.isNotBlank()) {
            val o = notify.overview(stored, deviceId)
            if (o != null) {
                code = stored; overview = o; unlocked = true
                // The server forgets the relay on every redeploy; the admin
                // phone remembers it and puts it back whenever the console opens.
                val t = settingsStore.settings.first()
                if (t.turnUrl.isNotBlank()) notify.setRelay(stored, deviceId, t.turnUrl, t.turnUser, t.turnPass)
            } else {
                // The server no longer honours the code: drop both copies.
                wallet.clearAdmin(); guard.unbind()
            }
        }
    }

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
                Text("Admin", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground)
                Text(
                    when {
                        unlocked -> "Axzora operator console"
                        guardNote.isNotBlank() -> guardNote
                        else -> "Restricted"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            if (unlocked) {
                IconButton(onClick = { scope.launch { refresh() } }) {
                    Icon(Icons.Rounded.Refresh, "Refresh", tint = accent)
                }
            }
        }

        /*
         * A different phone gets a closed door, not a login box.
         *
         * Showing the code field here was the hole: the seal would refuse to
         * open, but a copied code could still be typed in and claim the
         * console. The panel now states the situation and offers nothing to
         * type — the only way in is to clear this app's data and re-claim from
         * the operator's own device.
         */
        if (!unlocked && guardState == com.axzora.os.security.AdminDeviceGuard.State.BOUND_ELSEWHERE) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(50.dp), tint = accent)
                Spacer(Modifier.height(18.dp))
                Text("This console belongs to another device",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Axzora admin is bound to one phone. It stays open there, and it " +
                        "cannot be opened here — not with the code, and not from a backup. " +
                        "If this is the operator's new phone, wipe this app's data and claim " +
                        "admin again from the server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Column
        }

        if (!unlocked) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(50.dp), tint = accent)
                Spacer(Modifier.height(18.dp))
                Text("Admin access", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Enter the admin code. It's verified by the server, and admin can be " +
                        "open on up to 2 devices at a time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Admin code") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && code.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            keyboard?.hide()
                            val claim = notify.claim(code, deviceId, "Axzora admin")
                            val o = if (claim is ClaimResult.Ok) notify.overview(code, deviceId) else null
                            busy = false
                            when {
                                o != null -> {
                                    overview = o
                                    unlocked = true
                                    wallet.unlockAdmin(code)
                                    // Seal the code to this phone now that the
                                    // server has accepted it. From here the
                                    // console re-opens on this handset with no
                                    // retyping, and on no other handset at all.
                                    if (guard.bind(code)) {
                                        guardState = com.axzora.os.security.AdminDeviceGuard.State.BOUND_HERE
                                        guardNote = "Bound to this device · " + guard.hardwareLabel()
                                    }
                                    msg = ""
                                }
                                claim is ClaimResult.WrongCode -> msg = "Wrong code."
                                claim is ClaimResult.HeldElsewhere ->
                                    msg = "Admin is already open on 2 devices (${claim.holder}). Sign out on one first."
                                claim is ClaimResult.Failed ->
                                    msg = "Couldn't reach the server: ${claim.reason}"
                                else -> msg = "That code was rejected."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(if (busy) "Checking…" else "Unlock") }
                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(msg, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            return@Column
        }

        // ---- totals ----
        overview?.totals?.let { t ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stat("Users", "${t.users}", accent, Modifier.weight(1f))
                Stat("Circles", "${t.circles}", accent, Modifier.weight(1f))
                Stat("HP", "%.1f".format(t.happyPaisa), accent, Modifier.weight(1f))
                Stat("Entries", "${t.ledgerEntries}", accent, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdminTab.entries.forEach { t ->
                val sel = t == tab
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = if (sel) accent else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                    modifier = Modifier.weight(1f).clickable { tab = t },
                ) {
                    Text(
                        t.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(vertical = 9.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (msg.isNotBlank()) {
            Text(
                msg, style = MaterialTheme.typography.bodyMedium, color = accent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when (tab) {
            AdminTab.PEOPLE -> PeopleTab(overview?.people.orEmpty(), currency, accent)
            AdminTab.NOTIFY -> NotifyTab(
                people = overview?.people.orEmpty(),
                accent = accent,
                onSend = { title, text, target ->
                    scope.launch {
                        busy = true
                        keyboard?.hide()
                        when (val r = notify.send(code, deviceId, title, text, target)) {
                            is NotifyResult.Sent ->
                                msg = "Delivered to ${r.delivered} device" +
                                    if (r.delivered == 1) "" else "s"
                            NotifyResult.NotAdmin -> msg = "Admin code rejected."
                            NotifyResult.NoSuchId -> msg = "No user has that ID."
                            is NotifyResult.Failed -> msg = "Failed: ${r.reason}"
                        }
                        busy = false
                        refresh()
                    }
                },
                busy = busy,
            )
            AdminTab.MINT -> MintTab(
                people = overview?.people.orEmpty(),
                currency = currency,
                accent = accent,
                onMint = { uid, hp ->
                    scope.launch {
                        busy = true
                        keyboard?.hide()
                        msg = when (val r = ledger.mint(uid, hp, code, deviceId)) {
                            is LedgerResult.Ok -> "Minted %.3f HP — new balance %.3f".format(hp, r.balanceHp)
                            LedgerResult.NotAdmin -> "Admin code rejected."
                            is LedgerResult.Offline -> "Can't reach the ledger: ${r.reason}"
                            else -> "Mint failed."
                        }
                        busy = false
                        refresh()
                    }
                },
                busy = busy,
            )
            AdminTab.AI -> AiTab(
                overview = overview,
                agentUrl = adminAgentUrl,
                accent = accent,
                onSetAgent = { url -> scope.launch { settingsStore.setAdminAgentUrl(url) } },
            )
            AdminTab.RELAY -> RelayTab(
                url = adminSettings.turnUrl, user = adminSettings.turnUser, pass = adminSettings.turnPass,
                accent = accent, busy = busy,
                onSave = { u, usr, pw ->
                    scope.launch {
                        busy = true
                        settingsStore.setTurn(u, usr, pw)
                        msg = when (val r = notify.setRelay(code, deviceId, u, usr, pw)) {
                            "ok" -> "Relay saved. Calls across networks will use it."
                            "cleared" -> "Relay cleared. Calls fall back to the Axzora voice relay."
                            else -> "Couldn't save relay: $r"
                        }
                        busy = false
                    }
                },
            )
        }
    }
}

/**
 * Calls between two phones on different networks need a relay (TURN) when
 * no direct path exists. Without one the app still connects — voice goes
 * through the Axzora server — but a real TURN gives the best quality and
 * video. Any provider works; Metered.ca and Cloudflare have free tiers.
 */
@Composable
private fun RelayTab(
    url: String, user: String, pass: String,
    accent: Color, busy: Boolean,
    onSave: (String, String, String) -> Unit,
) {
    var u by remember(url) { mutableStateOf(url) }
    var usr by remember(user) { mutableStateOf(user) }
    var pw by remember(pass) { mutableStateOf(pass) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("CALL RELAY (TURN)", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.alpha(0.45f))
        }
        item {
            Text(
                "Phones on the same Wi-Fi connect directly. Across networks (Jio to Airtel, " +
                    "home to office) a relay is needed. Without one, AxzoraOS still connects the " +
                    "call through its own server — voice only, a little more delay. Paste a TURN " +
                    "relay here for full quality and video. Free: metered.ca → TURN server → " +
                    "credentials, or Cloudflare Calls.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.7f),
            )
        }
        item {
            OutlinedTextField(value = u, onValueChange = { u = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("TURN URL(s), comma separated") },
                supportingText = { Text("e.g. turn:a.relay.metered.ca:80, turn:a.relay.metered.ca:443?transport=tcp") })
        }
        item {
            OutlinedTextField(value = usr, onValueChange = { usr = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("Username") })
        }
        item {
            OutlinedTextField(value = pw, onValueChange = { pw = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("Credential / password") })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(u.trim(), usr.trim(), pw.trim()) }, enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Save & apply to server") }
                OutlinedButton(onClick = { u = ""; usr = ""; pw = ""; onSave("", "", "") }, enabled = !busy) { Text("Clear") }
            }
        }
        item {
            Text(
                "Saved on this phone too: every time you open Admin it is pushed to the server " +
                    "again, so a server restart never loses it.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.55f),
            )
        }
    }
}

/**
 * The Admin AI assistant — a private agent inside the console. It asks a
 * configurable OpenAI-compatible endpoint (your local brain, or the fx agent
 * once it's serving) with the current admin state as context, so the operator
 * can ask things like "who has the most Happy Paisa?" or "draft a notice".
 */
@Composable
private fun AiTab(
    overview: com.axzora.os.wallet.AdminOverview?,
    agentUrl: String,
    accent: Color,
    onSetAgent: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var endpoint by remember(agentUrl) { mutableStateOf(agentUrl) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Ask the admin AI about your users, balances, or to draft a notice. " +
                    "It runs on your own agent — the local brain by default, or fx.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        item {
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("Ask the admin AI") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
        }
        item {
            Button(
                enabled = !busy && prompt.isNotBlank() && endpoint.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; answer = "Thinking…"
                        val people = overview?.people.orEmpty()
                        val ctx = "There are ${people.size} registered users. Top balances: " +
                            people.sortedByDescending { it.balanceHp }.take(5)
                                .joinToString(", ") { "${it.name}=${"%.1f".format(it.balanceHp)}HP" }
                        answer = askAgent(endpoint, ctx, prompt)
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "…" else "Ask") }
        }
        if (answer.isNotBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(answer, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Text("AGENT ENDPOINT", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        }
        item {
            OutlinedTextField(
                value = endpoint, onValueChange = { endpoint = it },
                label = { Text("OpenAI-compatible URL (local brain or fx)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Default is your PC's brain. Point it at fx's server to use fx.") },
            )
        }
        item {
            OutlinedButton(onClick = { onSetAgent(endpoint) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save agent endpoint")
            }
        }
    }
}

/** POST to an OpenAI-compatible /v1/chat/completions endpoint. */
private suspend fun askAgent(endpoint: String, context: String, prompt: String): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val root = endpoint.trim().trimEnd('/')
            val url = if (root.endsWith("/v1/chat/completions")) root else "$root/v1/chat/completions"
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 60000
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            val messages = org.json.JSONArray()
                .put(org.json.JSONObject().put("role", "system").put("content",
                    "You are the Axzora Admin AI, assisting the operator of AxzoraOS. Be concise, " +
                        "practical, and never invent user data. Current admin state: $context"))
                .put(org.json.JSONObject().put("role", "user").put("content", prompt))
            val body = org.json.JSONObject()
                .put("model", "qwen2.5:7b-instruct").put("messages", messages).put("stream", false)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (!ok) return@withContext "Agent error ${c.responseCode}: ${text.take(180)}"
            org.json.JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }.getOrElse { "Couldn't reach the agent at $endpoint — ${it.message}" }
    }

@Composable
private fun Stat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
            Text(
                label.uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun PeopleTab(people: List<AdminUser>, currency: Currency, accent: Color) {
    if (people.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No users registered yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(people, key = { it.uid }) { p ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.5f)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.name.trim().take(1).uppercase().ifBlank { "?" },
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            p.uid, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        )
                        val queued = p.pendingNotices + p.pendingTransfers
                        if (queued > 0) {
                            Text(
                                "$queued waiting to be collected",
                                style = MaterialTheme.typography.bodySmall, color = accent,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("%.3f HP".format(p.balanceHp),
                            style = MaterialTheme.typography.titleMedium, color = accent)
                        Text(
                            Currencies.format(p.balanceHp, currency),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotifyTab(
    people: List<AdminUser>,
    accent: Color,
    onSend: (title: String, text: String, target: String) -> Unit,
    busy: Boolean,
) {
    var title by remember { mutableStateOf("Axzora") }
    var text by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("ALL") }

    // imePadding keeps the compose box above the keyboard — the message field
    // used to sit underneath it, which is why it looked like it wasn't there.
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Message") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("SEND TO", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        }
        item {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = if (target == "ALL") accent else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                modifier = Modifier.fillMaxWidth().clickable { target = "ALL" },
            ) {
                Text(
                    "Everyone (${people.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (target == "ALL") MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(13.dp),
                )
            }
        }
        items(people, key = { it.uid }) { p ->
            val sel = target == p.uid
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = if (sel) accent else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth().clickable { target = p.uid },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name, style = MaterialTheme.typography.titleMedium,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        p.uid, style = MaterialTheme.typography.bodySmall,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }
        item {
            Button(
                onClick = { if (text.isNotBlank()) onSend(title.ifBlank { "Axzora" }, text, target) },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Campaign, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Sending…" else "Send notification")
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun MintTab(
    people: List<AdminUser>,
    currency: Currency,
    accent: Color,
    onMint: (uid: String, hp: Double) -> Unit,
    busy: Boolean,
) {
    var amount by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Happy Paisa to create") },
                supportingText = {
                    val hp = amount.toDoubleOrNull() ?: 0.0
                    Text("= " + Currencies.format(hp, currency))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("CREDIT TO", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        }
        items(people, key = { it.uid }) { p ->
            val sel = target == p.uid
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = if (sel) accent else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth().clickable { target = p.uid },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name, style = MaterialTheme.typography.titleMedium,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            p.uid, style = MaterialTheme.typography.bodySmall,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        )
                    }
                    Text(
                        "%.3f".format(p.balanceHp),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else accent,
                    )
                }
            }
        }
        item {
            Button(
                onClick = {
                    val hp = amount.toDoubleOrNull()
                    if (hp != null && hp > 0 && target.isNotBlank()) onMint(target, hp)
                },
                enabled = !busy && target.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (busy) "Minting…" else "Mint") }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
