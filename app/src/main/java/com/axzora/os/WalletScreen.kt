package com.axzora.os

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axzora.os.wallet.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import com.axzora.os.wallet.Rail

private enum class WalletTab { HOME, PAY, OFFERS, HISTORY }

@Composable
fun WalletScreen(
    displayName: String,
    serverUrl: String,
    phoneNumber: String = "",
    currencyCode: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallet = remember { WalletStore(context) }
    val net = remember(serverUrl) { WalletNet { serverUrl } }
    val ledger = remember(serverUrl) { LedgerClient { serverUrl } }
    val currency = remember(currencyCode) {
        if (currencyCode.isBlank()) Currencies.forLocale() else Currencies.byCode(currencyCode)
    }
    val inbox = remember(serverUrl) { WalletInbox(context, wallet, net) }
    val st by wallet.state.collectAsState(initial = WalletState())

    var tab by remember { mutableStateOf(WalletTab.HOME) }
    var success by remember { mutableStateOf<String?>(null) }

    val accent = MaterialTheme.colorScheme.primary
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // Mint an ID on first open and publish it so others can pay this device.
    LaunchedEffect(displayName, serverUrl, phoneNumber) {
        val uid = wallet.ensureUid()
        if (serverUrl.isBlank()) return@LaunchedEffect
        net.register(uid, displayName.ifBlank { "Axzora user" }, phoneNumber)
    }

    // While the wallet is open, check for money every few seconds. Anything
    // that lands is credited and raises a notification.
    LaunchedEffect(st.uid, serverUrl) {
        if (st.uid.isBlank()) return@LaunchedEffect
        if (serverUrl.isBlank()) return@LaunchedEffect
        while (true) {
            val got = inbox.poll(st.uid)
            if (got > 0) success = "Money received"
            // The ledger is the record that counts; adopt it when reachable so
            // a reinstalled or out-of-sync phone corrects itself.
            ledger.balance(st.uid)?.let { server ->
                if (kotlin.math.abs(server - st.balanceHp) > 1e-6) wallet.syncBalance(server)
            }
            delay(6000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 44.dp)) {

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back",
                        tint = MaterialTheme.colorScheme.onBackground)
                }
                Text("Happy Paisa", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.weight(1f))
                if (st.isAdmin) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accent.copy(alpha = 0.2f),
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text("ADMIN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }

            BalanceCard(st.balanceHp, currency, st.uid, accent) {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(st.uid))
                success = "ID copied"
            }

            Spacer(Modifier.height(10.dp))
            StakingCard(ledger, st.uid, st.balanceHp, accent) { success = it }

            Spacer(Modifier.height(10.dp))
            FinanceAdvisorCard(st.balanceHp, st.txns, accent)

            Spacer(Modifier.height(10.dp))
            ReputationCard(accent)

            Spacer(Modifier.height(14.dp))

            // tab strip
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WalletTab.entries.forEach { t ->
                    val selected = t == tab
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) accent else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.weight(1f).clickable { tab = t },
                    ) {
                        Text(
                            t.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.padding(vertical = 9.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Box(Modifier.weight(1f)) {
                when (tab) {
                    WalletTab.HOME -> WalletHome(wallet, ledger, st, serverUrl, accent) { success = it }
                    WalletTab.PAY -> PayTab(wallet, net, ledger, st, displayName, currency, accent) { success = it }
                    WalletTab.OFFERS -> OffersTab(wallet, st, currency, accent) { success = it }
                    WalletTab.HISTORY -> HistoryTab(st, currency)
                }
            }
        }

        // celebratory overlay after a successful money movement
        success?.let { msg ->
            SuccessOverlay(msg, accent) { success = null }
        }
    }
}

/* --------------------------- balance card --------------------------- */

@Composable
private fun BalanceCard(hp: Double, currency: Currency, uid: String, accent: Color, onCopyId: () -> Unit) {
    // Count-up animation whenever the balance changes.
    val shown by animateFloatAsState(
        targetValue = hp.toFloat(),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "balance",
    )
    val shimmer by rememberInfiniteTransition(label = "sh").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerPhase",
    )

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Box(
            Modifier
                .background(
                    Brush.linearGradient(
                        listOf(accent, accent.copy(alpha = 0.55f), accent.copy(alpha = 0.85f)),
                        start = Offset(shimmer * 900f - 300f, 0f),
                        end = Offset(shimmer * 900f + 300f, 400f),
                    )
                )
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Savings, null,
                        tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("HAPPY PAISA BALANCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    // The balance is the one number that should feel weighty,
                    // so it gets the display serif rather than the body face.
                    Text(
                        "%.3f".format(shown),
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("HP", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("In-app points ledger (demo — not real currency)",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.6f))
                if (uid.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("YOUR ID", fontSize = 9.sp, letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                        Spacer(Modifier.width(8.dp))
                        Text(uid, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = Color.White, letterSpacing = 1.sp)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Rounded.ContentCopy, "Copy ID",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp).clickable { onCopyId() },
                        )
                    }
                }
            }
        }
    }
}

/* --------------------------- staking card --------------------------- */

@Composable
private fun StakingCard(
    ledger: LedgerClient,
    uid: String,
    balanceHp: Double,
    accent: Color,
    onSuccess: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var staked by remember { mutableStateOf(0.0) }
    var reward by remember { mutableStateOf(0.0) }
    var locked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    // Load the live staking position whenever the screen is shown.
    LaunchedEffect(uid) {
        if (uid.isBlank()) return@LaunchedEffect
        when (val r = ledger.staking(uid)) {
            is StakeResult.Ok -> { staked = r.stakedHp; reward = r.rewardHp; locked = r.locked }
            else -> {}
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒 STAKING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.width(8.dp))
                Text("demo 6% · 30-day lock", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            if (staked > 0) {
                Text("%.3f HP staked".format(staked), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("+ %.4f HP reward".format(reward), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                if (locked) Text("locked", fontSize = 11.sp, color = accent)
            } else {
                Text("No active stake", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && staked == 0.0 && balanceHp > 0,
                    onClick = {
                        scope.launch {
                            busy = true
                            val amt = if (balanceHp >= 10) 10.0 else balanceHp
                            when (val r = ledger.stake(uid, amt)) {
                                is StakeResult.Ok -> { staked = r.stakedHp; locked = r.locked; msg = "Staked $amt HP"; onSuccess(msg) }
                                is StakeResult.Failed -> msg = r.reason
                                else -> msg = "Couldn't stake right now"
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Stake") }
                Button(
                    enabled = !busy && staked > 0,
                    onClick = {
                        scope.launch {
                            busy = true
                            when (val r = ledger.unstake(uid)) {
                                is StakeResult.Ok -> { staked = 0.0; locked = false; msg = "Unstaked"; onSuccess(msg) }
                                is StakeResult.Locked -> msg = "Still locked"
                                is StakeResult.NoStake -> msg = "No stake"
                                is StakeResult.Failed -> msg = r.reason
                                else -> msg = "Couldn't unstake"
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Unstake") }
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/* --------------------------- finance advisor --------------------------- */

@Composable
private fun FinanceAdvisorCard(balanceHp: Double, txns: List<Txn>, accent: Color) {
    val report = remember(balanceHp, txns) { FinanceAdvisor.analyze(balanceHp, txns) }
    val score = report.score.toInt()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📊 AI FINANCE GUARDIAN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.weight(1f))
                Text("${score}/100", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = scoreColor(score))
            }
            Spacer(Modifier.height(6.dp))
            // health bar
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (score / 100f) },
                color = scoreColor(score),
                trackColor = accent.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Spacer(Modifier.height(10.dp))
            // key figures
            Row(Modifier.fillMaxWidth()) {
                FinanceStat("Net", (if (report.netDelta >= 0) "+" else "") + String.format("%.0f", report.netDelta), Modifier.weight(1f))
                FinanceStat("Save rate", "${(report.saveRatio * 100).toInt()}%", Modifier.weight(1f))
                FinanceStat("Spend/day", String.format("%.1f", report.spendingPerDay), Modifier.weight(1f))
                FinanceStat("30d proj.", String.format("%.0f", report.predictedSavings30d), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            // top categories
            if (report.spendCategories.isNotEmpty()) {
                Text("TOP SPEND", fontSize = 10.sp, color = accent)
                report.spendCategories.entries.take(3).forEach { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.key, fontSize = 12.sp, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
                        Text(String.format("%.0f HP", entry.value), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // insights
            report.insights.forEach { line ->
                Text("• $line", fontSize = 11.5.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun FinanceStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> Color(0xFF7CFC9C)
    score >= 60 -> Color(0xFFFFD54F)
    else -> Color(0xFFFF8A80)
}

/* --------------------------- home tab --------------------------- */

@Composable
private fun WalletHome(
    wallet: WalletStore,
    ledger: LedgerClient,
    st: WalletState,
    serverUrl: String,
    accent: Color,
    onSuccess: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rails = remember { OfflineRails.detect(context) }
    var adminCode by remember { mutableStateOf("") }
    // Restore the stored code so minting still works after leaving and
    // returning to this screen, where the entry field is no longer shown.
    LaunchedEffect(st.isAdmin) {
        if (st.isAdmin && adminCode.isBlank()) adminCode = wallet.adminCode()
    }
    var mintAmount by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var noticeTitle by remember { mutableStateOf("Axzora") }
    var noticeText by remember { mutableStateOf("") }
    var noticeTarget by remember { mutableStateOf("ALL") }
    var sending by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        Text("PAYMENT RAILS", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RailPill("Internet", Icons.Rounded.Language, true, accent, Modifier.weight(1f))
            RailPill("Bluetooth", Icons.Rounded.Bluetooth, rails.bluetoothOn, accent, Modifier.weight(1f))
            RailPill("NFC", Icons.Rounded.Nfc, rails.nfcOn, accent, Modifier.weight(1f))
            RailPill("QR", Icons.Rounded.QrCode2, true, accent, Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        if (!st.isAdmin) {
            Text("ADMIN — verified by the server, not stored in this app",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = adminCode, onValueChange = { adminCode = it },
                    label = { Text("Operator code") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = adminCode.isNotBlank(),
                    onClick = {
                    scope.launch {
                        // The server decides: it checks the code and that a
                        // seat is free (admin can be open on up to 2 devices).
                        val dev = wallet.ensureUid()
                        msg = when (val r = AdminNotify { serverUrl }
                            .claim(adminCode, dev, "Axzora " + dev.takeLast(4))) {
                            ClaimResult.Ok -> {
                                wallet.unlockAdmin(adminCode)
                                "Admin unlocked."
                            }
                            ClaimResult.WrongCode -> "Wrong code."
                            is ClaimResult.HeldElsewhere ->
                                "Admin is already open on 2 devices (${r.holder}). Sign out on one first."
                            is ClaimResult.Failed -> "Couldn't reach the server: ${r.reason}"
                        }
                    }
                }) { Text("Unlock") }
            }
        } else {
            AdminCard(title = "Mint Happy Paisa", accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = mintAmount,
                    onValueChange = { mintAmount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("HP to create") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val amt = mintAmount.toDoubleOrNull()
                    if (amt == null || amt <= 0) { msg = "Enter an amount."; return@Button }
                    scope.launch {
                        // Minting is authorised and applied by the ledger, so an
                        // admin code lifted out of the APK is useless on its own.
                        when (val r = ledger.mint(st.uid, amt, adminCode, st.uid)) {
                            is LedgerResult.Ok -> {
                                // Credit the DURABLE local wallet by the amount minted, so it
                                // survives a server that later loses its ledger file.
                                wallet.credit(amt, "Mint", "Admin mint", Rail.SYSTEM)
                                mintAmount = ""
                                onSuccess("Minted %.3f HP".format(amt))
                            }
                            LedgerResult.NotAdmin -> {
                                // Let them re-enter rather than being stuck as
                                // an "admin" the server refuses to accept.
                                wallet.clearAdmin()
                                msg = "The server rejected that admin code — enter it again."
                            }
                            is LedgerResult.Offline -> msg = "Can't reach the ledger: ${r.reason}"
                            else -> msg = "Mint failed."
                        }
                    }
                }) { Text("Mint") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0, 5.0, 25.0).forEach { q ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            when (val r = ledger.mint(st.uid, q, adminCode, st.uid)) {
                                is LedgerResult.Ok -> {
                                    wallet.credit(q, "Mint", "Admin mint", Rail.SYSTEM)
                                    onSuccess("Minted %.0f HP".format(q))
                                }
                                LedgerResult.NotAdmin -> {
                                    wallet.clearAdmin()
                                    msg = "The server rejected that admin code — enter it again."
                                }
                                is LedgerResult.Offline -> msg = "Can't reach the ledger: ${r.reason}"
                                else -> msg = "Mint failed."
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("+${q.toInt()}") }
                }
            }
            }
        }

        if (st.isAdmin) {
            Spacer(Modifier.height(14.dp))
            AdminCard(
                title = "Notify users",
                subtitle = "Push a notification to one person, or to everyone using Axzora.",
                accent = accent,
            ) {
            OutlinedTextField(
                value = noticeTitle, onValueChange = { noticeTitle = it },
                label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = noticeText, onValueChange = { noticeText = it },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = noticeTarget, onValueChange = { noticeTarget = it },
                label = { Text("Send to") },
                placeholder = { Text("ALL, or AXZ-XXXX-XXXX") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (noticeText.isBlank()) { msg = "Write a message first."; return@Button }
                    scope.launch {
                        sending = true
                        val r = AdminNotify { serverUrl }.send(
                            adminCode = adminCode,
                            deviceId = st.uid,
                            title = noticeTitle.ifBlank { "Axzora" },
                            text = noticeText,
                            target = noticeTarget.ifBlank { "ALL" },
                        )
                        sending = false
                        when (r) {
                            is NotifyResult.Sent -> {
                                noticeText = ""
                                onSuccess("Notified ${r.delivered} device${if (r.delivered == 1) "" else "s"}")
                            }
                            NotifyResult.NotAdmin -> {
                                wallet.clearAdmin()
                                msg = "The server rejected that admin code — enter it again."
                            }
                            NotifyResult.NoSuchId -> msg = "No Axzora user has that ID."
                            is NotifyResult.Failed -> msg = "Couldn't send: ${r.reason}"
                        }
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Campaign, null)
                Spacer(Modifier.width(8.dp))
                Text(if (sending) "Sending…" else "Send notification")
            }
            }
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 13.sp, color = accent)
        }
    }
}

/**
 * A bordered section for one admin job.
 *
 * The admin controls were previously a bare run of fields with no separation,
 * so it was not obvious where minting ended and notifying began.
 */
@Composable
private fun AdminCard(
    title: String,
    accent: Color,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun RailPill(
    label: String, icon: ImageVector, on: Boolean, accent: Color, modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (on) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, if (on) accent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f)),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp),
                tint = if (on) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 10.sp,
                color = if (on) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

/* --------------------------- pay tab --------------------------- */

@Composable
private fun PayTab(
    wallet: WalletStore,
    net: WalletNet,
    ledger: LedgerClient,
    st: WalletState,
    displayName: String,
    currency: Currency,
    accent: Color,
    onSuccess: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var rail by remember { mutableStateOf(Rail.INTERNET) }
    var msg by remember { mutableStateOf("") }
    var voucherQr by remember { mutableStateOf<String?>(null) }
    var incoming by remember { mutableStateOf("") }
    var resolvedName by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    // Resolve the recipient's ID to a name as it is typed, so the sender can
    // see who they are about to pay before committing.
    LaunchedEffect(to, rail) {
        resolvedName = null
        if (rail != Rail.INTERNET) return@LaunchedEffect
        val id = AxzoraId.normalize(to)
        if (!AxzoraId.isValid(id)) return@LaunchedEffect
        checking = true
        delay(350)
        resolvedName = net.lookup(id)
        checking = false
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("SEND", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = to, onValueChange = { to = it },
            label = { Text(if (rail == Rail.INTERNET) "Recipient's Axzora ID" else "Pay to (name)") },
            placeholder = { if (rail == Rail.INTERNET) Text("AXZ-XXXX-XXXX") },
            singleLine = true,
            trailingIcon = {
                when {
                    checking -> CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp, color = accent,
                    )
                    resolvedName != null -> Icon(Icons.Rounded.CheckCircle, null, tint = accent)
                    else -> {}
                }
            },
            supportingText = {
                resolvedName?.let { Text("Paying $it", color = accent) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Amount in HP") },
            supportingText = {
                val hp = amount.toDoubleOrNull() ?: 0.0
                Text("≈ " + Currencies.format(hp, currency))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Note (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        Text("HOW TO SEND", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Rail.INTERNET to Icons.Rounded.Language,
                Rail.BLUETOOTH to Icons.Rounded.Bluetooth,
                Rail.NFC to Icons.Rounded.Nfc,
                Rail.QR to Icons.Rounded.QrCode2,
            ).forEach { (r, ic) ->
                val sel = r == rail
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (sel) accent else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f).clickable { rail = r },
                ) {
                    Column(
                        Modifier.padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(ic, null, modifier = Modifier.size(17.dp),
                            tint = if (sel) MaterialTheme.colorScheme.onPrimary else accent)
                        Text(r.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 9.sp,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
            }
        }

        if (rail != Rail.INTERNET) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            ) {
                Text(
                    "Offline transfer has no shared ledger yet, so a voucher could be " +
                        "presented more than once. Use it only with people you trust until " +
                        "the blockchain ledger is added.",
                    fontSize = 11.sp, modifier = Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                val hp = amount.toDoubleOrNull()
                if (to.isBlank()) { msg = "Who are you paying?"; return@Button }
                @Suppress("NAME_SHADOWING")
                if (hp == null || hp <= 0) { msg = "Enter an amount."; return@Button }
                if (hp > st.balanceHp) { msg = "Not enough Happy Paisa."; return@Button }
                scope.launch {
                    msg = ""
                    if (rail == Rail.INTERNET) {
                        val id = AxzoraId.normalize(to)
                        if (!AxzoraId.isValid(id)) {
                            msg = "That isn't a valid Axzora ID (AXZ-XXXX-XXXX)."
                            return@launch
                        }
                        if (id == st.uid) { msg = "You can't pay yourself."; return@launch }

                        // The ledger moves both sides in one step, so there is
                        // no window where the money has left one account without
                        // arriving in the other. Nothing is debited locally
                        // first — a failed send simply changes nothing.
                        msg = "Sending…"
                        when (val r = ledger.transfer(st.uid, id, hp, note)) {
                            is LedgerResult.Ok -> {
                                wallet.syncBalance(r.balanceHp)
                                amount = ""; note = ""; to = ""
                                msg = ""
                                onSuccess("Sent %.3f HP to %s".format(hp, r.toName.ifBlank { id }))
                            }
                            LedgerResult.NoSuchId ->
                                msg = "No Axzora user has that ID. Nothing was sent."
                            is LedgerResult.Insufficient -> {
                                wallet.syncBalance(r.balanceHp)
                                msg = "Not enough Happy Paisa. Nothing was sent."
                            }
                            is LedgerResult.Offline ->
                                msg = "Can't reach the ledger: ${r.reason}. Nothing was sent."
                            else -> msg = "Payment failed. Nothing was sent."
                        }
                    } else {
                        if (!wallet.debit(hp, to.ifBlank { "Offline" }, note, rail)) {
                            msg = "Payment failed."; return@launch
                        }
                        voucherQr = Voucher.create(hp, displayName, note).encode()
                        amount = ""; note = ""
                        onSuccess("Voucher ready for %.3f HP".format(hp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.Bolt, null)
            Spacer(Modifier.width(8.dp))
            Text("Pay now", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        // QR voucher for the receiver to scan
        voucherQr?.let { payload ->
            Spacer(Modifier.height(18.dp))
            Text("SHOW THIS TO THE RECEIVER", fontSize = 11.sp, letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrView(payload, Modifier.size(220.dp))
            }
            TextButton(onClick = { voucherQr = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Done")
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("RECEIVE OFFLINE", fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = incoming, onValueChange = { incoming = it },
            label = { Text("Paste voucher text") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val v = Voucher.decode(incoming)
                if (v == null) { msg = "That isn't a valid Axzora voucher."; return@OutlinedButton }
                scope.launch {
                    // Credit locally first so accepting works with no signal,
                    // then settle. The ledger rejects a nonce it has already
                    // seen, which is what catches the same voucher being handed
                    // to two people while both were offline.
                    wallet.credit(v.hp, v.from, v.note.ifBlank { "Offline voucher" }, Rail.QR)
                    incoming = ""
                    onSuccess("Received %.3f HP from %s".format(v.hp, v.from))

                    when (val r = ledger.settle(st.uid, v.nonce, v.hp, v.from)) {
                        is LedgerResult.Ok -> wallet.syncBalance(r.balanceHp)
                        is LedgerResult.AlreadySettled -> {
                            // Someone else banked this voucher first: take it back.
                            wallet.debit(v.hp, v.from, "Voucher already used", Rail.SYSTEM)
                            ledger.balance(st.uid)?.let { wallet.syncBalance(it) }
                            msg = "That voucher was already used elsewhere — it has been reversed."
                        }
                        is LedgerResult.Offline ->
                            msg = "Held offline — will settle when you're back online."
                        else -> {}
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Accept voucher") }

        Spacer(Modifier.height(30.dp))
    }
}

/* --------------------------- offers tab --------------------------- */

@Composable
private fun OffersTab(
    wallet: WalletStore,
    st: WalletState,
    currency: Currency,
    accent: Color,
    onSuccess: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("All") }
    var msg by remember { mutableStateOf("") }

    val list = Coupons.ALL.filter { category == "All" || it.category == category }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Coupons.categories) { c ->
                val sel = c == category
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (sel) accent else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    modifier = Modifier.clickable { category = c },
                ) {
                    Text(c, fontSize = 12.sp,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }
        if (msg.isNotBlank()) {
            Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.code }) { c ->
                val owned = c.code in st.redeemed
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp,
                        if (owned) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.07f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                                .background(accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) { Text(c.emoji, fontSize = 22.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.brand, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground)
                            Text(c.title, fontSize = 13.sp, color = accent)
                            Text(c.detail, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                            if (owned) {
                                Spacer(Modifier.height(4.dp))
                                Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = 0.2f)) {
                                    Text(c.code, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (owned) {
                            Icon(Icons.Rounded.CheckCircle, "Redeemed", tint = accent)
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val ok = wallet.debit(
                                            c.costHp, c.brand, "Coupon ${c.code}", Rail.SYSTEM,
                                        )
                                        if (ok) {
                                            wallet.markRedeemed(c.code)
                                            onSuccess("Unlocked ${c.code}")
                                        } else msg = "Need %.2f HP for that offer.".format(c.costHp)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("%.2f HP".format(c.costHp), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* --------------------------- history tab --------------------------- */

@Composable
private fun HistoryTab(st: WalletState, currency: Currency) {
    if (st.txns.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No transactions yet",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(st.txns, key = { it.id }) { t ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val green = Color(0xFF22C55E)
                val red = Color(0xFFF43F5E)
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background((if (t.isCredit) green else red).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (t.isCredit) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast,
                        null, tint = if (t.isCredit) green else red, modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.party.ifBlank { "Unknown" }, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        listOfNotNull(
                            t.note.takeIf { it.isNotBlank() },
                            t.rail.name.lowercase(),
                            fmtTime(t.ts),
                        ).joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (if (t.isCredit) "+" else "") + "%.3f HP".format(t.hp),
                        fontWeight = FontWeight.Bold,
                        color = if (t.isCredit) Color(0xFF22C55E) else Color(0xFFF43F5E),
                    )
                    Text(Currencies.format(kotlin.math.abs(t.hp), currency), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
                }
            }
        }
    }
}

private fun fmtTime(ts: Long): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM, h:mm a")
        .format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

/* --------------------------- success animation --------------------------- */

@Composable
private fun SuccessOverlay(message: String, accent: Color, onDone: () -> Unit) {
    val scale = remember { Animatable(0.4f) }
    val ringScale = remember { Animatable(0.2f) }
    val ringAlpha = remember { Animatable(0.7f) }

    LaunchedEffect(message) {
        scale.snapTo(0.4f); ringScale.snapTo(0.2f); ringAlpha.snapTo(0.7f)
        launch { scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
        launch { ringScale.animateTo(2.2f, tween(900, easing = FastOutSlowInEasing)) }
        launch { ringAlpha.animateTo(0f, tween(900)) }
        delay(1700)
        onDone()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // expanding ring
                Canvas(Modifier.size(140.dp)) {
                    drawCircle(
                        color = accent.copy(alpha = ringAlpha.value),
                        radius = size.minDimension / 2 * ringScale.value,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                    )
                }
                // coin burst
                Canvas(Modifier.size(180.dp)) {
                    val n = 10
                    for (i in 0 until n) {
                        val ang = (2 * Math.PI * i / n).toFloat()
                        val d = (size.minDimension / 2) * (ringScale.value - 0.2f) * 0.5f
                        drawCircle(
                            color = accent.copy(alpha = ringAlpha.value),
                            radius = 5f,
                            center = center + Offset(cos(ang) * d, sin(ang) * d),
                        )
                    }
                }
                Box(
                    Modifier.scale(scale.value).size(96.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.7f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(message, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/* --------------------------- QR view --------------------------- */

@Composable
private fun QrView(payload: String, modifier: Modifier = Modifier) {
    val matrix = remember(payload) { runCatching { QrCode.encode(payload) }.getOrNull() }
    if (matrix == null) {
        Text("Voucher too long to show as QR", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error)
        return
    }
    Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
        Canvas(modifier.padding(12.dp)) {
            val n = matrix.size
            val cell = size.minDimension / n
            for (r in 0 until n) for (c in 0 until n) {
                if (matrix[r][c]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(c * cell, r * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReputationCard(accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallet = remember { WalletStore(context) }
    val rep = remember { com.axzora.os.wallet.Reputation(context) }
    var score by remember { mutableStateOf(rep.score()) }
    var count by remember { mutableStateOf(rep.contributions().size) }
    var cats by remember { mutableStateOf(rep.categoryScores()) }
    var proof by remember { mutableStateOf<String?>(null) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EmojiEvents, null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Human Value Score", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("$score/999 · $count contribution${if (count == 1) "" else "s"}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
                Button(onClick = {
                    scope.launch {
                        wallet.credit(10.0, "Proof of contribution", "Contribution",
                            com.axzora.os.wallet.Rail.SYSTEM)
                        rep.addContribution("Self-verified contribution", "community", 10.0,
                            rep.lastKnownLocation())
                        score = rep.score(); count = rep.contributions().size; cats = rep.categoryScores()
                        proof = rep.report()
                    }
                }) { Text("+ Log") }
            }
            Spacer(Modifier.height(8.dp))
            // Multi-dimension HVS chips.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cats.forEach { (k, v) ->
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.12f)) {
                        Text("${k.take(6)} $v", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            proof?.let { p ->
                Spacer(Modifier.height(6.dp))
                Text(p, fontSize = 11.sp, lineHeight = 15.sp, color = accent)
            }
            Spacer(Modifier.height(6.dp))
            Text("Earning Happy Paisa for a genuine contribution grows your score by category — " +
                "value reflects what you do, not what you spend. Ask Mr Happy \"what's my reputation?\".",
                fontSize = 11.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
            TextButton(onClick = { proof = rep.passport("") }, modifier = Modifier.padding(top = 4.dp)) {
                Text("🪪 My Happy Passport")
            }
        }
    }
}

