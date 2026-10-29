package com.axzora.os

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.axzora.os.call.CallBus
import com.axzora.os.call.CallManager
import com.axzora.os.call.CallService
import com.axzora.os.call.CallSignaling
import com.axzora.os.call.CallState
import com.axzora.os.call.IncomingCall
import com.axzora.os.assistant.Brain
import com.axzora.os.assistant.CommandEngine
import com.axzora.os.assistant.FloatingService
import com.axzora.os.assistant.VoiceController
import com.axzora.os.ceo.CeoAgent
import com.axzora.os.ceo.CeoDashboard
import com.axzora.os.ceo.MrHappyAgiScreen
import com.axzora.os.appshield.AppShield
import com.axzora.os.data.SettingsStore
import com.axzora.os.data.UserSettings
import com.axzora.os.launcher.AppInfo
import com.axzora.os.launcher.AppRepository
import com.axzora.os.location.FamilyMember
import com.axzora.os.live.LiveScreen
import com.axzora.os.location.LocationRepository
import com.axzora.os.location.LocationService
import com.axzora.os.net.ServerFinder
import com.axzora.os.ui.AccentPresets
import com.axzora.os.ui.AuroraBackground
import com.axzora.os.ui.pressBounce
import com.axzora.os.ui.revealFrom
import androidx.compose.animation.togetherWith
import com.axzora.os.ui.AxzoraTheme
import com.axzora.os.ui.rememberDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Screen {
    HOME, DRAWER, CHAT, VOICE, LOCATION, SETTINGS, PAY, MESSAGES, FAMILYCHAT, DM, WALLET, PEOPLE,
    NEARBY, ADMIN, PERMISSIONS, STYLE, DEVICES, AGENTS, LIVE, LICENSES, CEO, AGI, ACCESSIBILITY,
    SOUNDMESH,
}

/** One entry in the AI Agents hub — a persona the assistant adopts for a task. */
data class AgentDef(
    val name: String,
    val tagline: String,
    val emoji: String,
    val persona: String,
)

// Mr Happy is THE assistant — one voice for the whole OS. (The earlier
// roster of specialist agents was removed: one assistant who can do it all is
// simpler than eight who each do a slice.)
val AxzoraAgents = listOf(
    AgentDef("Mr Happy", "Your voice — controls the whole phone", "🤖",
        "You are Mr Happy, Axzora's hands-on voice assistant that can operate the entire phone."),
)

/** Open Mr Happy's screen. [wake] = start talking immediately. */
fun openMrHappy(context: android.content.Context, wake: Boolean = false) {
    runCatching {
        context.startActivity(
            Intent(context, com.axzora.happyreddy.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("axzora.mrhappy.wake", wake),
        )
    }
}

class MainActivity : ComponentActivity() {

    /** Set when the phone's assistant gesture summoned us. */
    val assistRequested = mutableStateOf(false)

    /** Set when launched from an incoming-call notification. */
    private val callAction = mutableStateOf<Triple<String, Boolean, Boolean>?>(null)

    /** Set when opened from the OpenRoom bridge (openWallet/openDevices/openAgi). */
    private val navTarget = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_ASSIST) assistRequested.value = true
        readCallIntent(intent)
        readBridgeTarget(intent)
        handleShieldRevert(intent)
        // Only a call may appear over the keyguard. Doing this on every launch
        // meant the home screen itself sat on top of the lock, so the phone
        // still counted as locked and demanded the PIN the moment any app was
        // opened from it.
        applyLockScreenFlags(callAction.value != null)
        // Keep the OpenRoom/backend servers alive so OpenRoom stays up (a
        // foreground wake-lock service prevents Doze from suspending them).
        com.axzora.os.openroom.KeepAliveService.start(this)
        // Huawei/EMUI & other OEMs kill background services for battery unless the
        // app is explicitly exempted — ask for that so the keep-alive really runs.
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching {
                val pm = getSystemService(android.os.PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        )
                            .setData(android.net.Uri.parse("package:$packageName"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        setContent { AxzoraRoot(callAction, navTarget) }
    }

    // singleTask: a second notification tap re-uses this instance rather than
    // creating another, so the new intent arrives here instead of onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) assistRequested.value = true
        readCallIntent(intent)
        readBridgeTarget(intent)
        handleShieldRevert(intent)
        // A call arriving while the app is already open still needs to show
        // over the lock.
        applyLockScreenFlags(callAction.value != null)
    }

    /** OpenRoom bridge → navigate to a top-level screen (WALLET/DEVICES/AGI). */
    private fun readBridgeTarget(intent: Intent?) {
        val t = intent?.getStringExtra("axzora.screen") ?: return
        navTarget.value = t
    }

    /** Tapping the App Shield "Revert" notification restores the settings. */
    private fun handleShieldRevert(intent: Intent?) {
        if (intent?.action == "axzora.shield.revert") {
            runCatching {
                com.axzora.os.appshield.AppShield.revertActive(this)
                androidx.core.app.NotificationManagerCompat.from(this).cancel(200)
            }
        }
    }

    // Composition survives the Activity being stopped, so visibility must come
    // from the lifecycle — otherwise a backgrounded app still reports itself as
    // on-screen and the service suppresses the ringing notification.
    override fun onStart() {
        super.onStart()
        CallBus.uiVisible = true
    }

    override fun onStop() {
        super.onStop()
        CallBus.uiVisible = false
    }

    /**
     * Whether this window may show over the keyguard.
     *
     * True only while answering a call, exactly like the system dialer.
     * The launcher itself must sit behind the lock, or Android never
     * considers the phone unlocked.
     */
    private fun applyLockScreenFlags(overKeyguard: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(overKeyguard)
            setTurnScreenOn(overKeyguard)
        } else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            @Suppress("DEPRECATION")
            if (overKeyguard) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /*
     * Keep a video call visible after the user leaves.
     *
     * Walking away from the app used to end the picture entirely — the call
     * carried on with audio but the other person's face was gone until you
     * came back. Now the window shrinks to a floating tile that stays on top
     * of whatever is opened next, which is what every video-call app does and
     * what people expect when they check something mid-call.
     *
     * On Android 12 and up the system handles the shrink itself the moment
     * the app is left, which animates properly under gesture navigation.
     * Below that there is only onUserLeaveHint, which the Home button fires.
     */
    private fun pipParams(): android.app.PictureInPictureParams =
        android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(9, 16))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(true)
            }
            .build()

    /** Called by the composition whenever the call's video state changes. */
    fun updateCallPip(videoLive: Boolean) {
        CallBus.videoCallLive = videoLive
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            setPictureInPictureParams(
                if (videoLive) pipParams()
                else android.app.PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(false).build()
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 12+ auto-enters, so acting here as well would request it twice.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!CallBus.videoCallLive) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // The tile is a few centimetres across: the UI has to drop everything
        // but the video, or the controls cover the person you are talking to.
        CallBus.setPip(isInPictureInPictureMode)
    }

    private fun readCallIntent(intent: Intent?) {
        val id = intent?.getStringExtra(CallService.EXTRA_CALL_ID) ?: return
        callAction.value = Triple(
            id,
            intent.getBooleanExtra(CallService.EXTRA_ANSWER_VIDEO, false),
            intent.getBooleanExtra(CallService.EXTRA_DECLINE, false),
        )
        intent.removeExtra(CallService.EXTRA_CALL_ID)
    }
}

@Composable
private fun AxzoraRoot(
    callAction: androidx.compose.runtime.MutableState<Triple<String, Boolean, Boolean>?> =
        remember { mutableStateOf(null) },
    navTarget: androidx.compose.runtime.MutableState<String?> =
        remember { mutableStateOf(null) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val store = remember { SettingsStore(context) }
    // null until DataStore delivers the first snapshot, so onboarding never
    // flashes for a user who has already completed it.
    val loaded by store.settings.collectAsState(initial = null)
    val settings = loaded ?: UserSettings()

    val appRepo = remember { AppRepository(context) }
    val engine = remember { CommandEngine(context, appRepo) }
    val locationRepo = remember { LocationRepository(context) }

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    // Recently launched apps, so the drawer can lead with what was just used.
    val recentPackages by appRepo.recentPackages.collectAsState(initial = emptyList())
    // Autonomous watches (§14): while the app is alive, check "watch <url> for
    // <keyword>" monitors and notify on a match. Bounded by Watchs' own caps.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { com.axzora.os.assistant.Watches.checkAndNotify(context) }
            delay(180_000)
        }
    }
    LaunchedEffect(Unit) {
        // Apps first: the assistant is useless until this list exists, and it
        // must not be delayed behind unrelated setup work.
        apps = appRepo.loadApps()
        engine.apps = apps
        store.ensureDeviceId()
    }

    // Find a backend that is actually reachable from this network and store it,
    // so a moved host never requires reinstalling the app.
    val finder = remember { ServerFinder(context) }
    LaunchedEffect(Unit) {
        // Keep re-checking rather than probing once at launch. A first attempt
        // can fail for reasons that clear on their own — no signal yet, a slow
        // first TLS handshake — and without a retry the app stays pointed at an
        // unreachable address until it is restarted.
        while (true) {
            val found = finder.find()
            if (found != settings.serverUrl) store.setServerUrl(found)
            val healthy = finder.probe(found, timeoutMs = 8000)
            delay(if (healthy) 60_000 else 10_000)
        }
    }

    LaunchedEffect(settings.displayName) { engine.userName = settings.displayName }

    // The assistant's key lives on the server, so the brain follows whatever
    // address the app resolved rather than being pinned at build time.
    LaunchedEffect(Unit) { engine.brain = Brain(
        baseUrl = { settings.serverUrl },
        localBrainUrl = { settings.localBrainUrl },
        onDeviceBrainUrl = { settings.onDeviceBrainUrl },
    ) }

    // Let the assistant drive the shell itself: "make it dark", "ember theme",
    // "animations off" all route through these into the settings store.
    LaunchedEffect(Unit) {
        engine.onSetTheme = { i ->
            scope.launch {
                store.setThemeStyle(i)
                store.setDarkMode(com.axzora.os.ui.ThemeStyles[i].dark)
            }
        }
        engine.onSetDark = { d -> scope.launch { store.setDarkMode(d) } }
        engine.onSetAnim = { a -> scope.launch { store.setAnimLevel(a) } }
    }

    // Over-the-air update check on launch: if the server has a newer build,
    // offer it. This is what ends hand-delivering an APK for every change.
    var updateAvailable by remember {
        mutableStateOf<com.axzora.os.update.Updater.Available?>(null)
    }
    // The address falls back to the built-in default: right after launch the
    // resolved serverUrl can still be blank (the finder hasn't stored one yet),
    // and an update check against a blank host silently fails and never retries.
    val updater = remember {
        com.axzora.os.update.Updater(context) {
            buildList {
                if (settings.serverUrl.isNotBlank()) add(settings.serverUrl)
                add(com.axzora.os.Config.DEFAULT_SERVER)
                addAll(com.axzora.os.Config.SERVER_CANDIDATES)
            }
        }
    }
    // Ask for notification permission once (Android 13+) so the update alert
    // can actually reach the user while the phone is in use.
    val startupPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Mic just granted → bring Mr Happy's ear up right away.
        if (result[Manifest.permission.RECORD_AUDIO] == true &&
            com.axzora.happyreddy.BootReceiver.alwaysOn(context)
        ) {
            com.axzora.happyreddy.MrHappyService.start(context)
        }
    }
    LaunchedEffect(Unit) {
        val want = mutableListOf<String>()
        // Microphone so Mr Happy can hear his name from the home screen.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) want += Manifest.permission.RECORD_AUDIO
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) want += Manifest.permission.POST_NOTIFICATIONS
        if (want.isNotEmpty()) startupPermLauncher.launch(want.toTypedArray())
    }

    // A shared checker: raises the in-app dialog AND posts a system
    // notification, so a running phone is told about the update, not only one
    // that was just cold-started.
    suspend fun checkForUpdate() {
        val found = runCatching { updater.check() }.getOrNull() ?: return
        updateAvailable = found
        com.axzora.os.update.UpdateNotifier.show(context, found)
    }

    LaunchedEffect(Unit) {
        // First a short burst (the free server may be cold and the resolved
        // address can still be settling), then keep re-checking on a slow loop
        // for as long as the launcher runs — which is essentially always.
        repeat(4) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) 2500L else 15_000L)
            checkForUpdate()
            if (updateAvailable != null) return@repeat
        }
        while (true) {
            kotlinx.coroutines.delay(30 * 60 * 1000L)   // every 30 minutes
            if (updateAvailable == null) checkForUpdate()
        }
    }

    // The on-device model, if one has been installed. Loading takes tens of
    // seconds, so it happens once in the background — the assistant answers
    // from its offline engine until the weights are ready.
    val localLlm = remember { com.axzora.os.assistant.LocalLlm(context) }
    LaunchedEffect(Unit) {
        engine.localLlm = localLlm
        if (localLlm.installed) localLlm.load()
    }

    // Counts resumes, so anything keyed on it re-runs when the user returns
    // from a system screen (granting overlay access, for instance).
    var lifecycleTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) lifecycleTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Coming back to the home screen re-checks for an update, so a freshly
    // published build is picked up without waiting for the 30-minute loop.
    LaunchedEffect(lifecycleTick) {
        if (lifecycleTick > 0 && updateAvailable == null) checkForUpdate()
    }

    // The bubble is a service, so it dies on reboot and can't start itself
    // before the overlay permission exists. Opening the app is the reliable
    // moment to bring it back — including right after the user grants it.
    LaunchedEffect(settings.bubbleEnabled, lifecycleTick) {
        if (settings.bubbleEnabled && FloatingService.canDrawOverlays(context)) {
            FloatingService.start(context)
        }
    }

    // Mr Happy's always-on ear. The home screen is the one place that is
    // reliably in the foreground, which is exactly what Android requires to
    // start a microphone service — so the launcher keeps him listening, even
    // if his own screen was never opened or was swiped away.
    LaunchedEffect(lifecycleTick) {
        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micOk && com.axzora.happyreddy.BootReceiver.alwaysOn(context)) {
            com.axzora.happyreddy.MrHappyService.start(context)
        }
    }

    // Keep the shared sync client pointed at whatever the user configured.
    LaunchedEffect(settings.serverUrl, settings.deviceId) {
        Graph.configure(settings.serverUrl, settings.deviceId)
    }

    // ---- voice calling ----
    var myUid by remember { mutableStateOf("") }
    // The private-chat peer currently open (uid to display name).
    var dmPeer by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) {
        myUid = com.axzora.os.wallet.WalletStore(context).ensureUid()
    }

    // ---- device hub ----
    val deviceHub = remember(settings.serverUrl) {
        com.axzora.os.devices.DeviceHub(context) { settings.serverUrl }
            .also { it.deviceId = settings.deviceId }
    }
    LaunchedEffect(settings.deviceId) { deviceHub.deviceId = settings.deviceId }
    LaunchedEffect(deviceHub, myUid) {
        engine.deviceHub = deviceHub
        engine.deviceUid = myUid
    }
    val signaling = remember(settings.serverUrl) { CallSignaling { settings.serverUrl } }
    val callManager = remember(settings.serverUrl) {
        CallManager(
            context = context,
            signaling = signaling,
            myUid = { myUid },
            myName = { settings.displayName.ifBlank { "Axzora user" } },
        )
    }
    val callInfo by callManager.call.collectAsState()
    val localVideo by callManager.localVideo.collectAsState()
    val remoteVideo by callManager.remoteVideo.collectAsState()
    val inPip by CallBus.pip.collectAsState()
    val groupInfo by callManager.group.collectAsState()
    val roomInvite by callManager.roomInvite.collectAsState()
    val audioHealth by callManager.audioHealth.collectAsState()

    /*
     * Tell the Activity when there is a picture worth keeping.
     *
     * Floating over other apps only makes sense while video is actually
     * flowing — a voice call has nothing to show, and leaving auto-PiP armed
     * afterwards would shrink the launcher itself the next time it is left.
     */
    // LocalContext is not always the Activity itself - a theme wrapper sits
    // in between often enough that a plain cast quietly yields null and the
    // floating window never arms.
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is MainActivity) c = c.baseContext
        c as? MainActivity
    }
    LaunchedEffect(
        callInfo.state, callInfo.video, callInfo.remoteVideo,
        groupInfo.active, groupInfo.video,
    ) {
        activity?.updateCallPip(
            (callInfo.state == CallState.ACTIVE &&
                (callInfo.video || callInfo.remoteVideo)) ||
                (groupInfo.active && (groupInfo.video ||
                    groupInfo.people.any { it.hasVideo }))
        )
    }
    var pendingIncoming by remember { mutableStateOf<IncomingCall?>(null) }

    // The service keeps listening when this screen is gone; while it is here,
    // the UI marks itself visible so the service doesn't also raise a
    // notification for the same call.
    DisposableEffect(Unit) {
        CallService.start(context)
        onDispose { }
    }

    val busCall by CallBus.pending.collectAsState()
    LaunchedEffect(busCall) {
        val incoming = busCall
        if (incoming == null) {
            // The caller gave up, or the server stopped offering the call.
            // Nothing polls for that while a call is merely ringing — the call
            // engine's own loop only starts once the call is answered — so
            // without this the ringing screen stayed up and the ringtone and
            // vibration ran until the app was force-stopped.
            if (callInfo.state == CallState.RINGING && callInfo.incoming) {
                callManager.hangUp()
                pendingIncoming = null
            }
            return@LaunchedEffect
        }
        // ENDED counts as free: after the first call the state stays ENDED,
        // and requiring IDLE meant no second call ever rang.
        val free = callInfo.state == CallState.IDLE || callInfo.state == CallState.ENDED
        if (free && incoming.callId != pendingIncoming?.callId) {
            pendingIncoming = incoming
            callManager.showIncoming(incoming)
            CallService.dismissRinging(context)
        }
    }

    // A notification action decided how to answer before this screen existed.
    LaunchedEffect(callAction.value, busCall) {
        val action = callAction.value ?: return@LaunchedEffect
        val (id, wantVideo, decline) = action
        val incoming = busCall?.takeIf { it.callId == id } ?: return@LaunchedEffect
        CallService.dismissRinging(context)
        if (decline) {
            callManager.hangUp()
            CallBus.clear()
        } else {
            pendingIncoming = incoming
            callManager.acceptCall(incoming, video = wantVideo)
        }
        callAction.value = null
    }

    // Belt and braces: also poll from the foreground, so a call still rings
    // instantly even if the service was killed by an aggressive OEM.
    LaunchedEffect(myUid, settings.serverUrl) {
        if (myUid.isBlank()) return@LaunchedEffect
        callManager.listen { incoming ->
            val free = callInfo.state == CallState.IDLE || callInfo.state == CallState.ENDED
            if (free && incoming.callId != pendingIncoming?.callId) {
                pendingIncoming = incoming
                callManager.showIncoming(incoming)
            }
        }
    }

    // release() must follow the manager it belongs to, or a rebuilt manager
    // leaves the previous one polling forever in the background.
    DisposableEffect(callManager) { onDispose { callManager.release() } }

    // Announce this device to the Axzora directory on every launch, not just
    // when the wallet is opened — otherwise someone who never visits Wallet
    // stays invisible to everyone else, and their "seen" time goes stale.
    LaunchedEffect(settings.serverUrl, settings.displayName, settings.phoneNumber) {
        if (settings.serverUrl.isBlank()) return@LaunchedEffect
        val walletStore = com.axzora.os.wallet.WalletStore(context)
        val net = com.axzora.os.wallet.WalletNet { settings.serverUrl }
        val uid = walletStore.ensureUid()
        while (true) {
            net.register(uid, settings.displayName.ifBlank { "Axzora user" }, settings.phoneNumber)
            delay(60_000) // keeps the online dot accurate
        }
    }

    // Being in a circle means being visible in it: once a code, a server and
    // location permission all exist, publishing resumes on its own (e.g. after
    // a reboot) instead of waiting for the user to flip the switch again.
    LaunchedEffect(settings.locationSharing, settings.familyCode, settings.serverUrl, settings.deviceId) {
        // Sharing starts only when the user turns it on — at onboarding, or
        // with the switch on the map screen. It used to be forced back on
        // here whenever a family code existed, which made "off" impossible:
        // the switch flipped itself back within a second.
        val ready = settings.locationSharing &&
            settings.familyCode.isNotBlank() &&
            settings.serverUrl.isNotBlank() &&
            settings.deviceId.isNotBlank() &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (ready) {
            LocationService.start(
                context, settings.familyCode,
                settings.displayName.ifBlank { "Me" },
                settings.serverUrl, settings.deviceId,
            )
        }
    }

    var screen by remember { mutableStateOf(Screen.HOME) }

    // Navigate to a top-level screen requested by the OpenRoom bridge.
    LaunchedEffect(navTarget.value) {
        val t = navTarget.value ?: return@LaunchedEffect
        // The OpenRoom Hub can jump to any AxzoraOS screen by name. Resolve the
        // whole enum (WALLET, VOICE, MESSAGES, ACCESSIBILITY, SOUNDMESH, …);
        // anything unrecognised lands on HOME.
        screen = runCatching { Screen.valueOf(t.uppercase()) }.getOrDefault(Screen.HOME)
        navTarget.value = null
    }
    // The AI-agent persona a Chat is currently scoped to (null = plain Axzora).
    var activeAgent by remember { mutableStateOf<AgentDef?>(null) }

    // The CEO system — Mr Happy as an orchestrator of sub-agents + worlds.
    val ceo = remember { CeoAgent(context) }

    // Summoned by the assistant gesture: open the conversation directly,
    // the way the phone's previous assistant would have.
    val hostActivity = context as? MainActivity
    LaunchedEffect(hostActivity?.assistRequested?.value) {
        if (hostActivity?.assistRequested?.value == true) {
            hostActivity.assistRequested.value = false
            // Mr Happy is the assistant of record, and it ships inside
            // Axzora - one app, one install.
            runCatching {
                context.startActivity(
                    Intent(context, com.axzora.happyreddy.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { screen = Screen.CHAT }
        }
    }

    val accentColor = AccentPresets[settings.accentIndex.coerceIn(0, AccentPresets.lastIndex)].second
    val themeStyle = com.axzora.os.ui.ThemeStyles[
        settings.themeStyle.coerceIn(0, com.axzora.os.ui.ThemeStyles.lastIndex)
    ]

    // The status and navigation bars are drawn by Android, not by us, so
    // they stayed stock white/black while everything else changed style —
    // a white band across the top of a Pitch-black theme. Painting them the
    // theme's background (with matching icon brightness) makes the screen
    // read as one surface, edge to edge.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
        val bar = android.graphics.Color.argb(
            255,
            (themeStyle.background.red * 255).toInt(),
            (themeStyle.background.green * 255).toInt(),
            (themeStyle.background.blue * 255).toInt(),
        )
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = bar
            window.navigationBarColor = bar
        }
        val insets = androidx.core.view.WindowCompat.getInsetsController(window, view)
        insets.isAppearanceLightStatusBars = !themeStyle.dark
        insets.isAppearanceLightNavigationBars = !themeStyle.dark
    }

    AxzoraTheme(darkTheme = themeStyle.dark, accent = accentColor, style = themeStyle) {
        updateAvailable?.let { up ->
            UpdateDialog(
                available = up,
                updater = updater,
                onDismiss = { updateAvailable = null },
            )
        }

        // Updating keeps the saved settings, so someone who onboarded on an
        // older build never saw the permissions added since. Ask them once.
        if (loaded != null && settings.onboarded &&
            settings.permsVersion < Config.PERMISSION_REVISION
        ) {
            PermissionsPage(accent = accentColor) {
                scope.launch { store.setPermsVersion(Config.PERMISSION_REVISION) }
            }
            return@AxzoraTheme
        }

        // First run: brand intro, name, and joining a family circle.
        if (loaded != null && !settings.onboarded) {
            OnboardingScreen(accent = accentColor) { name, code, server, share ->
                scope.launch {
                    if (name.isNotBlank()) store.setDisplayName(name)
                    if (code.isNotBlank()) store.setFamilyCode(code)
                    if (server.isNotBlank()) store.setServerUrl(server)
                    store.setOnboarded(true)
                    store.setPermsVersion(Config.PERMISSION_REVISION)
                    if (share) {
                        val id = store.ensureDeviceId()
                        store.setLocationSharing(true)
                        Graph.configure(server, id)
                        LocationService.start(context, code, name.ifBlank { "Me" }, server, id)
                    }
                }
            }
            return@AxzoraTheme
        }

        // Android's screen timeout does not know a call is in progress, so
        // during a call — where nobody is touching the screen — the display
        // simply slept, and waking it read as the call having closed. A call
        // holds the screen awake for as long as it lasts.
        //
        // Kept above the call screens rather than between them: the group call
        // returns early, so a copy placed further down never ran for the case
        // it was written for.
        val liveCall = callInfo.state != CallState.IDLE && callInfo.state != CallState.ENDED
        KeepScreenAwake(liveCall || groupInfo.active)

        // A group call takes over the whole screen, exactly as a one-to-one
        // call does - the two can never be live at once. A group that failed
        // stays up briefly for the same reason a failed one-to-one call does:
        // "couldn't open the group call" is worth reading.
        val groupEndedWithReason = !groupInfo.active && groupInfo.error.isNotBlank()
        LaunchedEffect(groupInfo.active, groupInfo.error) {
            if (groupEndedWithReason) {
                delay(5000)
                callManager.clearEnded()
            }
        }
        if (groupInfo.active || groupEndedWithReason) {
            // Adding people mid-call: the sheet sits over the call so nothing
            // is interrupted while the new guest is chosen and rung.
            var addingPeople by remember { mutableStateOf(false) }
            var addable by remember { mutableStateOf<List<com.axzora.os.wallet.Person>>(emptyList()) }
            val inCall = groupInfo.people.map { it.uid }.toSet() + myUid
            val slots = (4 - groupInfo.people.size).coerceAtLeast(0)

            LaunchedEffect(addingPeople) {
                if (addingPeople) {
                    addable = runCatching {
                        com.axzora.os.wallet.WalletNet { settings.serverUrl }.directory(myUid)
                    }.getOrDefault(emptyList())
                }
            }

            GroupCallScreen(
                info = groupInfo,
                accent = accentColor,
                eglBase = callManager.eglBase,
                localVideo = localVideo,
                onLeave = { callManager.leaveGroup() },
                onToggleMute = { callManager.toggleGroupMute() },
                onToggleSpeaker = { callManager.toggleGroupSpeaker() },
                onToggleVideo = { callManager.toggleGroupVideo() },
                onSwitchCamera = { callManager.switchCamera() },
                onAddPeople = { addingPeople = true },
                canAddMore = groupInfo.active && slots > 0,
                health = audioHealth,
            )

            if (addingPeople) {
                AddPeopleSheet(
                    people = addable,
                    alreadyIn = inCall,
                    remainingSlots = slots,
                    accent = accentColor,
                    onAdd = { picked ->
                        callManager.addToGroupCall(picked)
                        addingPeople = false
                    },
                    onCancel = { addingPeople = false },
                )
            }
            return@AxzoraTheme
        }

        // An invitation into a group. Nobody has dialled yet, so this is an
        // offer rather than a ringing call.
        roomInvite?.let { invite ->
            GroupInviteSheet(
                room = invite,
                accent = accentColor,
                onJoin = { withVideo -> callManager.joinGroupCall(invite, video = withVideo) },
                onDecline = { callManager.declineGroupInvite(invite) },
            )
            return@AxzoraTheme
        }

        // A call that ended with something to say stays up long enough to be
        // read. Without this the explanation — no microphone permission, the
        // other phone unreachable, no route between the two — was written to
        // the one state nothing draws, so every failure looked to the user
        // like the call simply vanishing.
        val endedWithReason = callInfo.state == CallState.ENDED && callInfo.error.isNotBlank()
        LaunchedEffect(callInfo.state, callInfo.error) {
            if (endedWithReason) {
                delay(5000)
                callManager.clearEnded()
            }
        }

        // A live call takes over the whole screen.
        if (endedWithReason || (callInfo.state != CallState.IDLE && callInfo.state != CallState.ENDED)) {
            CallScreen(
                pip = inPip,
                info = callInfo,
                accent = accentColor,
                eglBase = callManager.eglBase,
                localVideo = localVideo,
                remoteVideo = remoteVideo,
                onAccept = { withVideo ->
                    pendingIncoming?.let { callManager.acceptCall(it, video = withVideo) }
                },
                health = audioHealth,
                onHangUp = { callManager.hangUp(); pendingIncoming = null; CallBus.clear() },
                onToggleMute = { callManager.toggleMute() },
                onToggleSpeaker = { callManager.toggleSpeaker() },
                onToggleVideo = { callManager.toggleVideo() },
                onSwitchCamera = { callManager.switchCamera() },
            )
            return@AxzoraTheme
        }

        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            // Crossfade rather than a hard swap — the same content, arriving
            // instead of appearing. Kept short so it never feels like lag,
            // and dialled down or off by the Animations setting.
            val animLevel = settings.animLevel.coerceIn(0, 2)
            val animSpeed = when (settings.animSpeed.coerceIn(0, 2)) {
                0 -> 1.35f
                2 -> 0.6f
                else -> 1f
            }

            // The system back gesture should walk back to Home, the way every
            // launcher behaves — without this it simply did nothing.
            androidx.activity.compose.BackHandler(enabled = screen != Screen.HOME) {
                screen = Screen.HOME
            }

            androidx.compose.runtime.CompositionLocalProvider(
                com.axzora.os.ui.LocalAnimLevel provides animLevel,
                com.axzora.os.ui.LocalAnimSpeed provides animSpeed,
            ) {
            Column(Modifier.fillMaxSize()) {
            // Screens arrive in depth rather than flat: the new one swings in
            // on a perspective axis while the old one swings away and dims,
            // keeping the "I went one level deeper" feeling that a crossfade
            // threw away. Depth3DTransition reads the same motion dials this
            // app already exposes — Off swaps instantly, Essential keeps the
            // slide but drops the rotation, Full plays the 3D move.
            com.axzora.os.ui.Depth3DTransition(
                targetState = screen,
                modifier = Modifier.weight(1f),
                // Home is the root; everything else is a level in. Only used
                // to decide which way the swing goes, never for security.
                depth = { s -> if (s == Screen.HOME) 0 else 1 },
            ) { current ->
            when (current) {
                Screen.HOME -> HomeScreen(
                    settings = settings,
                    apps = apps,
                    appRepo = appRepo,
                    onLaunch = { appRepo.launch(it) },
                    onNavigate = { screen = it },
                )
                Screen.DRAWER -> DrawerScreen(
                    settings = settings,
                    apps = apps,
                    recentPackages = recentPackages,
                    onSetGrid = { scope.launch { store.setDrawerGrid(it) } },
                    onSetSort = { scope.launch { store.setDrawerSort(it) } },
                    onLaunch = { appRepo.launch(it) },
                    onToggleFavorite = { scope.launch { store.toggleFavorite(it) } },
                    onToggleHidden = { scope.launch { store.toggleHidden(it) } },
                    onBack = { screen = Screen.HOME },
                )
                Screen.CHAT -> ChatScreen(engine = engine, agent = activeAgent, onNavigate = { screen = it }, onBack = { val had = activeAgent != null; activeAgent = null; screen = if (had) Screen.AGENTS else Screen.HOME })
                Screen.VOICE -> VoiceScreen(engine = engine, onNavigate = { screen = it }, onBack = { screen = Screen.HOME })
                Screen.CEO -> CeoDashboard(
                    ceo = ceo,
                    onBack = { screen = Screen.HOME },
                )
                Screen.AGI -> MrHappyAgiScreen(
                    ceo = ceo,
                    onOpenMrHappy = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(context, com.axzora.happyreddy.MainActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    onNavigate = { screen = it },
                    onBack = { screen = Screen.HOME },
                )
                Screen.AGENTS -> AgentsScreen(
                    onPick = { agent ->
                        if (agent.name == "Mr Happy") {
                            // Mr Happy is the embedded Gemini HUD — hand off to it.
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(context, com.axzora.happyreddy.MainActivity::class.java)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure { activeAgent = agent; screen = Screen.CHAT }
                        } else {
                            activeAgent = agent
                            screen = Screen.CHAT
                        }
                    },
                    onOpenCeo = { screen = Screen.CEO },
                    onBack = { screen = Screen.HOME },
                )
                Screen.LOCATION -> LocationScreen(
                    settings = settings,
                    locationRepo = locationRepo,
                    onToggleSharing = { on ->
                        scope.launch {
                            store.setLocationSharing(on)
                            if (on) {
                                LocationService.start(
                                    context, settings.familyCode, settings.displayName,
                                    settings.serverUrl, settings.deviceId,
                                )
                            } else {
                                LocationService.stop(context)
                                // Withdraw from the group so we stop appearing on others' maps.
                                Graph.familySync.leave(settings.familyCode)
                            }
                        }
                    },
                    onBack = { screen = Screen.HOME },
                )
                Screen.STYLE -> {
                    var styleClock by remember { mutableStateOf(nowText()) }
                    LaunchedEffect(Unit) {
                        while (true) { styleClock = nowText(); delay(1000) }
                    }
                    StyleScreen(
                        settings = settings,
                        store = store,
                        clock = styleClock,
                        greetingLine = greeting() +
                            (if (settings.displayName.isNotBlank()) ", ${settings.displayName}" else ""),
                        onBack = { screen = Screen.SETTINGS },
                    )
                }

                Screen.PERMISSIONS -> PermissionsPage(accent = accentColor) {
                    scope.launch { store.setPermsVersion(Config.PERMISSION_REVISION) }
                    screen = Screen.SETTINGS
                }

                Screen.DEVICES -> DevicesScreen(
                    hub = deviceHub,
                    uid = myUid,
                    accent = accentColor,
                    onBack = { screen = Screen.HOME },
                )

                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    store = store,
                    scope = scope,
                    onOpenAdmin = { screen = Screen.ADMIN },
                    onOpenPermissions = { screen = Screen.PERMISSIONS },
                    onOpenStyle = { screen = Screen.STYLE },
                    onOpenLicenses = { screen = Screen.LICENSES },
                    onOpenAccessibility = { screen = Screen.ACCESSIBILITY },
                    localLlm = localLlm,
                    onBack = { screen = Screen.HOME },
                )
                Screen.LICENSES -> LicensesScreen(onBack = { screen = Screen.SETTINGS })
                Screen.SOUNDMESH -> com.axzora.os.soundmesh.SoundMeshScreen(onBack = { screen = Screen.HOME })
                Screen.ACCESSIBILITY -> AccessibilityScreen(onBack = { screen = Screen.SETTINGS })
                Screen.PAY -> PayScreen(onBack = { screen = Screen.HOME })
                Screen.NEARBY -> NearbyScreen(
                    displayName = settings.displayName,
                    onBack = { screen = Screen.HOME },
                )
                Screen.ADMIN -> AdminScreen(
                    serverUrl = settings.serverUrl,
                    currencyCode = settings.currencyCode,
                    onBack = { screen = Screen.SETTINGS },
                )
                Screen.PEOPLE -> PeopleScreen(
                    serverUrl = settings.serverUrl,
                    onBack = { screen = Screen.HOME },
                    onPay = { screen = Screen.WALLET },
                    onMessage = { p -> dmPeer = p.uid to p.name; screen = Screen.DM },
                    onCall = { p -> callManager.startCall(p.uid, p.name, video = false) },
                    onVideoCall = { p -> callManager.startCall(p.uid, p.name, video = true) },
                    onGroupCall = { chosen, withVideo ->
                        callManager.startGroupCall(
                            chosen.map { com.axzora.os.call.RoomMember(it.uid, it.name) },
                            video = withVideo,
                            title = if (withVideo) "Group video call" else "Group call",
                        )
                    },
                )
                Screen.WALLET -> WalletScreen(
                    displayName = settings.displayName,
                    serverUrl = settings.serverUrl,
                    phoneNumber = settings.phoneNumber,
                    currencyCode = settings.currencyCode,
                    onBack = { screen = Screen.HOME },
                )
                Screen.MESSAGES -> ConversationsScreen(
                    settings = settings,
                    myUid = myUid,
                    onBack = { screen = Screen.HOME },
                    onOpenFamily = { screen = Screen.FAMILYCHAT },
                    onOpenDm = { uid, name -> dmPeer = uid to name; screen = Screen.DM },
                    onPickPerson = { screen = Screen.PEOPLE },
                )
                Screen.FAMILYCHAT -> MessagesScreen(settings = settings, onBack = { screen = Screen.MESSAGES })
                Screen.DM -> dmPeer?.let { (uid, name) ->
                    DmScreen(
                        settings = settings, myUid = myUid, peerUid = uid, peerName = name,
                        onBack = { screen = Screen.MESSAGES },
                    )
                } ?: run { screen = Screen.MESSAGES }
                Screen.LIVE -> LiveScreen(
                    serverUrl = settings.serverUrl,
                    viewerName = settings.displayName.ifBlank { "Me" },
                    onGoLive = { title ->
                        callManager.startLiveBroadcast(title)
                        screen = Screen.HOME
                    },
                    onJoinRoom = { room ->
                        callManager.joinGroupCall(
                            com.axzora.os.call.Room(
                                roomId = room.roomId, title = room.title,
                                host = room.host, hostName = room.hostName,
                                members = listOf(
                                    com.axzora.os.call.RoomMember(room.host, room.hostName),
                                ),
                            ),
                            video = true,
                        )
                        screen = Screen.HOME
                    },
                    onBack = { screen = Screen.HOME },
                )
            }
            } // end Depth3DTransition content
            // Persistent bottom tab bar, shown only on the main "shell" screens.
            val mainScreen = screen in setOf(
                Screen.HOME, Screen.AGI, Screen.CEO, Screen.WALLET, Screen.DEVICES,
                Screen.PEOPLE, Screen.SETTINGS,
            )
            if (mainScreen) {
                BottomNavBar(
                    current = screen,
                    onSelect = { tab ->
                        if (tab == Screen.AGI || tab == Screen.HOME) {
                            // these are in-app tabs
                            screen = tab
                        } else {
                            screen = tab
                        }
                    },
                )
            }
            } // end Column
        }
    }
}
}

/* ----------------------------- BOTTOM NAV ----------------------------- */

private data class NavTab(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val screen: Screen)

/** A compact data class for the tab list so the composable stays readable. */
private val MainNavTabs = listOf(
    NavTab(Icons.Rounded.Home, "Home", Screen.HOME),
    NavTab(Icons.Rounded.AutoAwesome, "AI", Screen.AGI),
    NavTab(Icons.Rounded.Insights, "CEO", Screen.CEO),
    NavTab(Icons.Rounded.AccountBalanceWallet, "Wallet", Screen.WALLET),
    NavTab(Icons.Rounded.Devices, "Devices", Screen.DEVICES),
    NavTab(Icons.Rounded.Settings, "System", Screen.SETTINGS),
)

@Composable
private fun BottomNavBar(current: Screen, onSelect: (Screen) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
    ) {
        Row(
            Modifier.padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainNavTabs.forEach { tab ->
                val selected = tab.screen == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                        .clickable { onSelect(tab.screen) }
                        .padding(vertical = 6.dp),
                ) {
                    Icon(
                        tab.icon, tab.label,
                        tint = if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp),
                    )
                    Text(tab.label, fontSize = 9.sp,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
            }
        }
    }
}

/* ----------------------------- HOME ----------------------------- */




/** Current battery percent, or -1 when unknown. */
internal fun batteryPct(context: Context): Int = runCatching {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
    bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
}.getOrDefault(-1)



@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AppTile(app: AppInfo, index: Int = 0, onClick: () -> Unit) {
    val anim = com.axzora.os.ui.LocalAnimLevel.current
    val hf = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // A touch should feel like it lands on something. The tile dips under the
    // finger and springs back — subtle enough not to slow the launch down.
    val press by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && anim > 0) 0.88f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.55f, stiffness = 900f,
        ),
        label = "press",
    )

    // Tiles arrive as a wave rather than all at once — only at full motion,
    // and only on first appearance, so scrolling stays instant.
    val speed = com.axzora.os.ui.LocalAnimSpeed.current
    var entered by remember { mutableStateOf(anim < 2) }
    LaunchedEffect(Unit) {
        if (!entered) {
            kotlinx.coroutines.delay(((index % 24) * 22 * speed).toLong())
            entered = true
        }
    }
    val entry by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween((260 * speed).toInt().coerceAtLeast(1)),
        label = "entry",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = press; scaleY = press
                alpha = entry
                translationY = (1f - entry) * 26f
                // The dip now lands on a surface with depth: as the tile
                // shrinks under the finger it tips a few degrees on X, so the
                // press reads as contact rather than a zoom-out. Same press
                // value as the scale, so the two can never drift apart, and
                // a raised virtual camera keeps the tilt from distorting the
                // icon. Off at motion level 0 because `press` stays at 1.
                rotationX = (1f - press) * 60f
                cameraDistance = 24f * density
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.35f)
            }
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    if (anim > 0) runCatching {
                        hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    onClick()
                },
                // Long-press opens the system App info page (uninstall, force
                // stop, permissions) — the manage-an-app gesture people expect
                // from a home screen, without digging through Settings.
                onLongClick = {
                    runCatching {
                        hf.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + app.packageName),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            .padding(vertical = 4.dp),
    ) {
        val primaryColor = MaterialTheme.colorScheme.primary
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
            // Micro-interaction: a soft accent halo blooms behind the icon on
            // touch, so the tile feels lit up under the finger, not just pressed.
            if (anim > 0) {
                val halo = androidx.compose.ui.graphics.Color.Transparent
                val glowAlpha = (if (pressed) 0.30f else 0f) * entry
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val c = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
                    val r = this.size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                primaryColor.copy(alpha = glowAlpha),
                                halo,
                            ),
                            center = c, radius = r,
                        ),
                        radius = r, center = c,
                    )
                }
            }
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)),
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            app.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
        )
    }
}

/* ----------------------------- DRAWER ----------------------------- */

@Composable
private fun DrawerScreen(
    settings: UserSettings,
    apps: List<AppInfo>,
    recentPackages: List<String>,
    onLaunch: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
    onSetGrid: (Boolean) -> Unit,
    onSetSort: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var editMode by remember { mutableStateOf(false) }
    val dim = rememberDimens()

    val visible = apps.filter { editMode || it.packageName !in settings.hiddenApps }
        .filter { it.label.contains(query, ignoreCase = true) }
        .let { list ->
            when (settings.drawerSort) {
                1 -> list.sortedByDescending { it.installedAt }
                else -> list.sortedBy { it.label.lowercase() }
            }
        }

    // Resolve recently launched packages to AppInfo, so the Recent row can lead
    // with the actual tiles. Apps that were uninstalled since launch simply drop.
    val recentApps = recentPackages.mapNotNull { pkg -> apps.find { it.packageName == pkg } }

    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true, modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
            )
            IconButton(onClick = { editMode = !editMode }) {
                Icon(
                    if (editMode) Icons.Rounded.Done else Icons.Rounded.Edit,
                    "Edit",
                    tint = if (editMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // View options: how the list is shaped and ordered. Kept as visible
        // chips rather than a hidden menu - a drawer is rearranged rarely,
        // and options nobody can find may as well not exist.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawerChip(settings.drawerGrid, Icons.Rounded.GridView, "Grid") { onSetGrid(true) }
            DrawerChip(!settings.drawerGrid, Icons.Rounded.ViewList, "List") { onSetGrid(false) }
            Spacer(Modifier.width(6.dp))
            DrawerChip(settings.drawerSort == 0, Icons.Rounded.SortByAlpha, "A-Z") { onSetSort(0) }
            DrawerChip(settings.drawerSort == 1, Icons.Rounded.Schedule, "New") { onSetSort(1) }
        }

        if (editMode) {
            Text(
                "  Tap the star to favorite, the eye to hide or show", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
        }

        // Recently opened apps lead the drawer, so the next launch is one tap
        // away. Hidden during edit mode, which is about the full list instead.
        if (!editMode && recentApps.isNotEmpty()) {
            Text(
                "Recent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                itemsIndexed(recentApps, key = { _, app -> app.packageName }) { i, app ->
                    Box(Modifier.width(68.dp), contentAlignment = Alignment.Center) {
                        AppTile(app, index = i) { onLaunch(app.packageName) }
                    }
                }
            }
        }

        // Edit mode always lists: the star and hide buttons need a row.
        if (settings.drawerGrid && !editMode) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(maxOf(settings.gridColumns, dim.gridColumns)),
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(visible, key = { _, app -> app.packageName }) { i, app ->
                    AppTile(app, index = i) { onLaunch(app.packageName) }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { if (!editMode) onLaunch(app.packageName) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(40.dp).clip(CircleShape))
                        Spacer(Modifier.width(16.dp))
                        Text(app.label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
                        if (editMode) {
                            val fav = app.packageName in settings.favoriteApps
                            val hidden = app.packageName in settings.hiddenApps
                            IconButton(onClick = { onToggleFavorite(app.packageName) }) {
                                Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite",
                                    tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                            }
                            IconButton(onClick = { onToggleHidden(app.packageName) }) {
                                Icon(if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Hide")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One pill in the drawer's view-options row. */
@Composable
private fun DrawerChip(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surface,
        border = if (selected) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, null, modifier = Modifier.size(15.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label, fontSize = 12.sp,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/* ----------------------------- CHAT ----------------------------- */

// ============================================================
// AI AGENTS HUB
// ============================================================
@Composable
private fun AgentsScreen(onPick: (AgentDef) -> Unit, onOpenCeo: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        TopRow("AI Agents", onBack)
        OutlinedButton(
            onClick = onOpenCeo,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp),
        ) {
            Text("🧠 Mr Happy AI CEO — 8 sub-agents + World Engine")
        }
        Text(
            "Pick a specialist. Each is a focused persona your assistant becomes for the task — Mr Happy also controls the whole phone by voice.",
            fontSize = 13.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(AxzoraAgents) { i, agent -> AgentCard(agent, i) { onPick(agent) } }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentDef, index: Int, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val featured = agent.name == "Mr Happy"
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (featured) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (featured) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth().height(134.dp).revealFrom(index)
            .pressBounce(pressedScale = 0.95f) { onClick() },
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(agent.emoji, fontSize = 30.sp)
            Spacer(Modifier.weight(1f))
            Text(agent.name, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(2.dp))
            Text(agent.tagline, fontSize = 11.5.sp, lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f))
        }
    }
}

private data class ChatMsg(val text: String, val fromUser: Boolean)

@Composable
private fun ChatScreen(engine: CommandEngine, agent: AgentDef? = null, onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    val messages = remember(agent) {
        mutableStateListOf(
            ChatMsg(
                if (agent != null)
                    "${agent.emoji} ${agent.name} here — ${agent.tagline.replaceFirstChar { it.lowercase() }}. What do you need?"
                else "Hi! I'm Axzora. Ask me anything, or tell me what to do.",
                false,
            ),
        )
    }
    LaunchedEffect(agent) { engine.agentPersona = agent?.persona }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun send() {
        val text = input.trim()
        if (text.isBlank() || thinking) return
        messages.add(ChatMsg(text, true))
        input = ""
        // The reply may need a round trip, so the send returns immediately and
        // the answer lands when it lands.
        scope.launch {
            thinking = true
            val history = messages.dropLast(1).map { Brain.Turn(it.text, it.fromUser) }
            val result = engine.ask(text, history)
            thinking = false
            messages.add(ChatMsg(result.reply, false))
            when (result.navigateTo) {
                "location" -> onNavigate(Screen.LOCATION)
                "pay" -> onNavigate(Screen.PAY)
                "wallet" -> onNavigate(Screen.WALLET)
                "people" -> onNavigate(Screen.PEOPLE)
                "nearby" -> onNavigate(Screen.NEARBY)
                "messages" -> onNavigate(Screen.MESSAGES)
                "settings" -> onNavigate(Screen.SETTINGS)
                "devices" -> onNavigate(Screen.DEVICES)
                "agents" -> onNavigate(Screen.AGENTS)
            }
        }
    }

    LaunchedEffect(messages.size, thinking) {
        val n = messages.size + if (thinking) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        TopRow(agent?.name ?: "Assistant", onBack)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            items(messages) { msg -> ChatBubble(msg) }
            if (thinking) item { ThinkingBubble() }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp).imePadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("Message Axzora") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { send() }, enabled = !thinking) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send")
            }
        }
    }
}

/** Three dots that breathe while the assistant is working out an answer. */
@Composable
private fun ThinkingBubble() {
    val t = rememberInfiniteTransition(label = "think")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                repeat(3) { i ->
                    val a by t.animateFloat(
                        0.25f, 1f,
                        infiniteRepeatable(
                            tween(600, delayMillis = i * 180),
                            RepeatMode.Reverse,
                        ),
                        label = "dot$i",
                    )
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(7.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = a)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg) {
    val dim = rememberDimens()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (msg.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = dim.bubbleMax),
        ) {
            Text(
                msg.text,
                Modifier.padding(12.dp),
                color = if (msg.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/* ----------------------------- VOICE ----------------------------- */

@Composable
private fun VoiceScreen(engine: CommandEngine, onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val voice = remember { VoiceController(context) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("Tap the mic and ask me anything.") }
    val scope = rememberCoroutineScope()
    // Kept so a spoken conversation has the same continuity as a typed one.
    val spoken = remember { mutableStateListOf<Brain.Turn>() }

    var hasMic by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    DisposableEffect(Unit) { onDispose { voice.stop() } }

    fun listen() {
        if (!hasMic) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        voice.start(
            onPartial = { heard = it },
            onResult = { text ->
                heard = text
                scope.launch {
                    reply = "…"
                    val r = engine.ask(text, spoken.toList())
                    spoken.add(Brain.Turn(text, fromUser = true))
                    spoken.add(Brain.Turn(r.reply, fromUser = false))
                    reply = r.reply
                    when (r.navigateTo) {
                        "location" -> onNavigate(Screen.LOCATION)
                        "pay" -> onNavigate(Screen.PAY)
                        "wallet" -> onNavigate(Screen.WALLET)
                        "people" -> onNavigate(Screen.PEOPLE)
                        "nearby" -> onNavigate(Screen.NEARBY)
                        "messages" -> onNavigate(Screen.MESSAGES)
                        "settings" -> onNavigate(Screen.SETTINGS)
                        "devices" -> onNavigate(Screen.DEVICES)
                        "agents" -> onNavigate(Screen.AGENTS)
                    }
                }
            },
            onState = { listening = it },
            onError = { reply = it },
        )
    }

    Column(Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TopRow("Voice", onBack)
        Spacer(Modifier.weight(1f))
        Text(heard.ifBlank { "…" }, fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(16.dp))
        Text(reply, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .background(if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                .clickable { listen() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, "Listen", modifier = Modifier.size(44.dp),
                tint = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
        }
        Text(if (listening) "Listening…" else "Tap to speak", modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        Spacer(Modifier.height(24.dp))
    }
}

/* ----------------------------- LOCATION ----------------------------- */

@Composable
private fun LocationScreen(
    settings: UserSettings,
    locationRepo: LocationRepository,
    onToggleSharing: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasLocation by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    var center by remember { mutableStateOf<GeoPoint?>(null) }
    var members by remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    suspend fun refresh() {
        if (!hasLocation) return
        status = "Locating…"
        val loc = locationRepo.currentLocation() ?: locationRepo.lastKnown()
        if (loc != null) {
            center = GeoPoint(loc.latitude, loc.longitude)
            Graph.familySync.publish(settings.familyCode, settings.displayName, loc.latitude, loc.longitude)
        } else {
            status = "Couldn't get a fix. Is location on?"
            return
        }
        members = Graph.familySync.members(settings.familyCode)
        // Surface sync problems instead of showing a silently empty map.
        val err = Graph.familySync.lastError
        status = when {
            settings.serverUrl.isBlank() -> "No server URL set — only your own position is shown."
            settings.familyCode.isBlank() -> "No family code set — see Settings."
            err != null -> err
            else -> "Updated ${nowText().first} · ${members.size} sharing"
        }
    }

    // Members-only fetch: no GPS, no self-publish. The vehicle loop uses this
    // so it can update the map often without hammering the location provider,
    // which was heavy enough to hang the app when called every second.
    suspend fun refreshMembers() {
        if (settings.familyCode.isBlank()) return
        members = Graph.familySync.members(settings.familyCode)
    }

    LaunchedEffect(hasLocation) { if (hasLocation) refresh() }
    // Poll while this screen is open so family members appear as they move.
    LaunchedEffect(hasLocation, settings.serverUrl, settings.familyCode) {
        while (hasLocation) {
            // Near-real-time: people and vehicles slide across the map rather
            // than jumping every twenty seconds.
            delay(5_000)
            refresh()
        }
    }

    // A simulated vehicle: a moving car on the live world map, to show the
    // vehicle layer working end to end before there's real vehicle hardware.
    // It drives a slow circle around the current position and publishes like
    // any other entity, so every phone on the circle sees it move.
    var vehicleOn by remember { mutableStateOf(false) }
    LaunchedEffect(vehicleOn, settings.familyCode) {
        if (!vehicleOn) return@LaunchedEffect
        val c = center ?: return@LaunchedEffect
        val http = Graph.familySync as? com.axzora.os.location.HttpFamilySync
            ?: return@LaunchedEffect
        var angle = 0.0
        val radius = 0.0016  // ~180m in degrees latitude
        while (vehicleOn) {
            angle += 0.12
            val lat = c.latitude + radius * kotlin.math.cos(angle)
            val lon = c.longitude + radius * kotlin.math.sin(angle) /
                kotlin.math.cos(Math.toRadians(c.latitude))
            // Heading is the tangent to the circle, in compass degrees.
            val heading = ((Math.toDegrees(angle) + 90.0) % 360.0).toFloat()
            http.publishVehicle(
                settings.familyCode, "VEH-DEMO-1", "Satyug Car",
                lat, lon, heading, speed = 32f,
            )
            refreshMembers()
            delay(1_500)
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        TopRow("Location", onBack)

        if (!hasLocation) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.LocationOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Location permission needed", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
                Text("Family sharing is opt-in. Your location is only shared while you turn sharing on.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Grant location") }
                Spacer(Modifier.weight(1f))
            }
            return@Column
        }

        // Map
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp))) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(16.0)
                    }
                },
                update = { map ->
                    center?.let { map.controller.setCenter(it) }
                    map.overlays.clear()
                    members.forEach { m ->
                        val marker = Marker(map)
                        marker.position = GeoPoint(m.latitude, m.longitude)
                        marker.title = m.name
                        if (m.kind == "vehicle") {
                            // A car, pointed the way it is travelling.
                            marker.icon = ContextCompat.getDrawable(
                                context, R.drawable.ic_car_marker,
                            )
                            marker.rotation = -m.heading  // osmdroid rotates counter-clockwise
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        } else {
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        map.overlays.add(marker)
                    }
                    map.invalidate()
                },
                modifier = Modifier.fillMaxSize(),
            )

            // God's Evil Eye (nazar) protect amulet, floating over the family map.
            // A pulsing blue eye + gold halo. Symbolic (a protective reminder),
            // never a real force — the map underneath is the real, shared data.
            EvilEyeAmulet(Modifier.align(Alignment.TopEnd).padding(14.dp))
        }

        val accent = MaterialTheme.colorScheme.primary
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share my location", fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            if (settings.familyCode.isBlank()) "Set a circle code in Settings"
                            else "Circle: ${settings.familyCode} · ${members.size} member${if (members.size == 1) "" else "s"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                        if (status.isNotBlank()) {
                            val bad = status.contains("Can't reach") || status.contains("timed out") ||
                                status.contains("Unknown host") || status.contains("No server")
                            Text(status, fontSize = 12.sp,
                                color = if (bad) MaterialTheme.colorScheme.error else accent)
                        }
                    }
                    Switch(checked = settings.locationSharing, onCheckedChange = { onToggleSharing(it) })
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Rounded.Refresh, "Refresh")
                    }
                }

                // Vehicle layer: drop a live moving car onto the world map. It
                // shares the same circle, so anyone on it watches the car drive.
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.DirectionsCar, null,
                        tint = accent, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Simulate a vehicle", fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            if (vehicleOn) "Satyug Car is driving the circle — watch the map"
                            else "Puts a live moving car on the world map",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                    Switch(checked = vehicleOn, onCheckedChange = { vehicleOn = it })
                }

                if (members.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        Modifier.heightIn(max = 170.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(members) { m ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Coloured avatar disc with the member's initial
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(
                                        Brush.linearGradient(
                                            listOf(accent, accent.copy(alpha = 0.55f))
                                        )
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        m.name.trim().take(1).uppercase().ifBlank { "?" },
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (m.isSelf) "${m.name} (you)" else m.name,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    Text(
                                        "%.4f, %.4f".format(m.latitude, m.longitude) + agoSuffix(m.updatedEpochMs),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                    )
                                }
                                // Live dot for anyone seen in the last 2 minutes
                                if (System.currentTimeMillis() - m.updatedEpochMs < 120_000) {
                                    Box(
                                        Modifier.size(9.dp).clip(CircleShape)
                                            .background(Color(0xFF22C55E))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * God's Evil Eye (nazar) protect amulet. A pulsing blue eye with a dark pupil,
 * ringed by a gold "protect" halo, with an honest caption. Symbolic only — a
 * protective reminder over the real family map, never a real force.
 */
@Composable
fun EvilEyeAmulet(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "evil-eye")
        .animateFloat(
            0.85f, 1.15f,
            infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "pulse",
        )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Draw the eye with a compact canvas: gold halo, blue rings, pupil.
        androidx.compose.foundation.Canvas(Modifier.size(64.dp * pulse)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // gold halo
            drawCircle(Color(0xFFFBBF24), radius = size.minDimension * 0.48f, center = androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
            // outer blue ring
            drawCircle(Color(0xFF0B2A52), radius = size.minDimension * 0.40f, center = androidx.compose.ui.geometry.Offset(cx, cy))
            // iris / mid blue
            drawCircle(Color(0xFF1976D2), radius = size.minDimension * 0.30f, center = androidx.compose.ui.geometry.Offset(cx, cy))
            // inner light blue
            drawCircle(Color(0xFF4FC3F7), radius = size.minDimension * 0.20f, center = androidx.compose.ui.geometry.Offset(cx, cy))
            // pupil
            drawCircle(Color(0xFF0A1A2E), radius = size.minDimension * 0.11f, center = androidx.compose.ui.geometry.Offset(cx, cy))
            // glint
            drawCircle(Color(0xFFEAF6FF), radius = size.minDimension * 0.035f, center = androidx.compose.ui.geometry.Offset(cx - size.minDimension * 0.05f, cy - size.minDimension * 0.05f))
        }
        Spacer(Modifier.width(2.dp))
        Text("👁 God's Evil Eye", fontSize = 9.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.SemiBold)
        Text("watching over your family", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

/** " · 3m ago" style suffix, or empty when the timestamp is unusable. */
private fun agoSuffix(ts: Long): String {
    if (ts <= 0) return ""
    val secs = (System.currentTimeMillis() - ts) / 1000
    return when {
        secs < 60 -> " · just now"
        secs < 3600 -> " · ${secs / 60}m ago"
        secs < 86_400 -> " · ${secs / 3600}h ago"
        else -> " · ${secs / 86_400}d ago"
    }
}

/* ----------------------------- SETTINGS ----------------------------- */

/** Editable AI-server setting (URL / key / model), saved to axzora_ai_options. */
@Composable
private fun AiServerField(label: String, prefKey: String, placeholder: String, secret: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("axzora_ai_options", Context.MODE_PRIVATE) }
    var value by remember { mutableStateOf(prefs.getString(prefKey, "") ?: "") }
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            value = v
            prefs.edit().putString(prefKey, v.trim()).apply()
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MemoryCard() {
    val context = LocalContext.current
    val memory = remember { com.axzora.os.assistant.Memory(context) }
    var facts by remember { mutableStateOf(memory.all()) }
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Assistant memory",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { memory.clear(); facts = memory.all() }) { Text("Clear all") }
            }
            Spacer(Modifier.height(8.dp))
            if (facts.isEmpty()) {
                Text(
                    "Nothing saved yet. Tell me \"remember my … is …\" and it'll appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            } else {
                facts.take(8).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${f.subject}: ${f.value}", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { memory.forget(f.subject); facts = memory.all() }) {
                            Icon(
                                Icons.Rounded.Close, "Forget",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
                if (facts.size > 8) {
                    Text(
                        "…and ${facts.size - 8} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SettingsScreen(
    settings: UserSettings,
    store: SettingsStore,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenAdmin: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenStyle: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenAccessibility: () -> Unit,
    localLlm: com.axzora.os.assistant.LocalLlm,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(top = 48.dp).verticalScroll(rememberScrollState())) {
        TopRow("Settings", onBack)
        Column(Modifier.padding(20.dp)) {

            // Put first on purpose: it is the one setting people go looking
            // for and could not find when it sat at the bottom of the list.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AutoAwesome, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Floating assistant",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = settings.bubbleEnabled,
                            onCheckedChange = { want ->
                                if (!want) {
                                    FloatingService.stop(context)
                                    scope.launch { store.setBubble(false) }
                                } else if (FloatingService.canDrawOverlays(context)) {
                                    FloatingService.start(context)
                                    scope.launch { store.setBubble(true) }
                                } else {
                                    // "Display over other apps" is special access —
                                    // Android grants it only on its own screen, so
                                    // record the intent and start on the way back.
                                    scope.launch { store.setBubble(true) }
                                    FloatingService.requestPermission(context)
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Puts a small Axzora button over every app. Tap it to ask " +
                            "something without leaving what you're doing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    if (settings.bubbleEnabled && !FloatingService.canDrawOverlays(context)) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Android needs one more permission for this, and only " +
                                "grants it on its own screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { FloatingService.requestPermission(context) }) {
                            Text("Allow display over other apps")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            /* ---- Mr Happy: the one assistant ---- */
            var alwaysOn by remember {
                mutableStateOf(com.axzora.happyreddy.BootReceiver.alwaysOn(context))
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🤖", fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Mr Happy — always listening",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = alwaysOn,
                            onCheckedChange = { want ->
                                alwaysOn = want
                                com.axzora.happyreddy.BootReceiver.setAlwaysOn(context, want)
                                if (want) com.axzora.happyreddy.MrHappyService.start(context)
                                else com.axzora.happyreddy.MrHappyService.stop(context)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Say “Mr Happy” from anywhere — the home screen, inside another " +
                            "app, even after his screen is closed — and he answers, " +
                            "already listening. Survives reboots.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                    Spacer(Modifier.height(12.dp))
                    // The phone-level assistant slot (long-press home / the
                    // assistant gesture). Android only lets the user pick it on
                    // its own screen, so this opens that picker.
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }) {
                        Text("Make Mr Happy the phone's default assistant")
                    }
                    Text(
                        "Pick “Axzora OS” as the digital assistant app, and the assistant " +
                            "gesture / long-press home opens Mr Happy instead of Google.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    Spacer(Modifier.height(16.dp))

                    // Restricted mode: the invasive tools (Termux shell, SMS,
                    // screen reading) are off unless the user opts in. On by
                    // default — a cloud model should not reach those by default.
                    var restricted by remember {
                        mutableStateOf(com.axzora.happyreddy.MrHappyService.isRestricted(context))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Restricted mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = restricted,
                            onCheckedChange = { want ->
                                restricted = want
                                com.axzora.happyreddy.MrHappyService.setRestricted(context, want)
                            },
                        )
                    }
                    Text(
                        "While on, Mr Happy cannot run shell commands in Termux, send " +
                            "SMS, or read your screen, even if you ask him to. Turn it " +
                            "off only if you want those.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    Spacer(Modifier.height(10.dp))
                    // On-device model: prefer the local llama-server when ON.
                    // Default OFF — DeepSeek is smarter, so it's the default.
                    var useLocal by remember {
                        mutableStateOf(
                            context.getSharedPreferences("axzora_ai_options", Context.MODE_PRIVATE)
                                .getBoolean("use_local_brain", false)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Use on-device model",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = useLocal,
                            onCheckedChange = { want ->
                                useLocal = want
                                context.getSharedPreferences("axzora_ai_options", Context.MODE_PRIVATE)
                                    .edit().putBoolean("use_local_brain", want).apply()
                            },
                        )
                    }
                    Text(
                        "When on, Mr Happy answers with your local model (llama-server on " +
                            "this phone) — offline, free, private. It falls back to DeepSeek " +
                            "if the local model isn't running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Model settings — the API key is held on the Axzora server (not in the app). The model name below is used for cloud reasoning.",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    AiServerField("Server URL", "ai_url", "https://api.deepseek.com/chat/completions", secret = false)
                    Spacer(Modifier.height(6.dp))
                    AiServerField("API key (optional)", "ai_key", "sk-…", secret = true)
                    Spacer(Modifier.height(6.dp))
                    AiServerField("Model", "ai_model", "deepseek-chat", secret = false)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Assistant memory — see and clear what it remembers (§12).
            MemoryCard()

            Spacer(Modifier.height(14.dp))

            // Reachable at any time. The first-run screen is easy to miss, and
            // is skipped entirely for anyone who updates from an older build.
            val missing = missingPermissions(context)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable { onOpenPermissions() },
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Lock, null,
                        tint = if (missing == 0) MaterialTheme.colorScheme.primary
                        else Color(0xFFF59E0B),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Permissions",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (missing == 0) "Everything Axzora needs is allowed"
                            else "$missing still to allow — calls, map or offline " +
                                "sharing may not work",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // Mr Happy controls the phone through the Accessibility service
            // (tap / type / read / auto-send). This is the one settings entry
            // that keeps every one of those working.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable { onOpenAccessibility() },
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Accessibility, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Accessibility",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Mr Happy control — tap, type, read & auto-send",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))


            SectionTitle("Your name")
            OutlinedTextField(
                value = settings.displayName,
                onValueChange = { scope.launch { store.setDisplayName(it) } },
                placeholder = { Text("Shown on the home screen & map") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Appearance")

            // Setting Axzora as the phone's home screen is what makes this
            // theme the phone's default face — on any Android, no root.
            var isHome by remember { mutableStateOf(isDefaultLauncher(context)) }
            val homeLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { isHome = isDefaultLauncher(context) }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = if (isHome) null
                else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        if (!isHome) {
                            homeLauncher.launch(defaultLauncherIntent(context))
                        } else {
                            // Android does not let an app un-set itself as
                            // home; the settings page is where that happens.
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Home, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Phone's home screen",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (isHome) "Axzora is the default. Tap to switch back to " +
                                "your old launcher any time."
                            else "Tap to make Axzora the default. Works on any Android; " +
                                "your old launcher stays installed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    if (isHome) {
                        Icon(
                            Icons.Rounded.CheckCircle, null,
                            tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable { onOpenStyle() },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.AutoAwesome, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Home & lock style",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Clock faces, atmospheres, and the lock screen wallpaper",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable { onOpenLicenses() },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Info, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Open Source Licenses",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Third-party components and their licenses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight, null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Theme", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Row(
                Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                com.axzora.os.ui.ThemeStyles.forEachIndexed { i, st ->
                    val sel = settings.themeStyle == i
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                            scope.launch {
                                store.setThemeStyle(i)
                                // Screens that only know light/dark keep up.
                                store.setDarkMode(st.dark)
                            }
                        }.padding(4.dp),
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(st.background)
                                .border(
                                    if (sel) 2.5.dp else 1.dp,
                                    if (sel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(13.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            st.name, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(
                                alpha = if (sel) 1f else 0.6f,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Animations", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Row(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Off", "Simple", "Full").forEachIndexed { i, label ->
                    val sel = settings.animLevel.coerceIn(0, 2) == i
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        border = if (sel) null
                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.clickable { scope.launch { store.setAnimLevel(i) } },
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                            fontSize = 13.sp,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (settings.animLevel > 0) {
                Spacer(Modifier.height(4.dp))
                Text("Speed", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                Row(
                    Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Relaxed", "Normal", "Fast").forEachIndexed { i, label ->
                        val sel = settings.animSpeed.coerceIn(0, 2) == i
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (sel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                            border = if (sel) null
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.clickable { scope.launch { store.setAnimSpeed(i) } },
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                                fontSize = 13.sp,
                                color = if (sel) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Accent color", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentPresets.forEachIndexed { i, (_, color) ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(color)
                            .clickable { scope.launch { store.setAccent(i) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == settings.accentIndex) Icon(Icons.Rounded.Check, null, tint = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Home grid columns: ${settings.gridColumns}", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Slider(
                value = settings.gridColumns.toFloat(),
                onValueChange = { scope.launch { store.setColumns(it.toInt()) } },
                valueRange = 3f..6f, steps = 2,
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Currency")
            Text("Happy Paisa is one global unit — this only changes the currency it is shown in. Balances are unaffected.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            val active = remember(settings.currencyCode) {
                if (settings.currencyCode.isBlank()) com.axzora.os.wallet.Currencies.forLocale()
                else com.axzora.os.wallet.Currencies.byCode(settings.currencyCode)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.axzora.os.wallet.Currencies.ALL) { c ->
                    val selected = c.code == active.code
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.clickable { scope.launch { store.setCurrency(c.code) } },
                    ) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("${c.symbol} ${c.code}", fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface)
                            Text("1 HP = ${c.symbol}${"%,.0f".format(c.perHp)}", fontSize = 10.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle("Your phone number")
            Text("Optional. Adding it lets other Axzora users call you from the People screen. It is never shown to anyone outside Axzora.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.phoneNumber,
                onValueChange = { scope.launch { store.setPhoneNumber(it) } },
                label = { Text("Phone number") },
                placeholder = { Text("+91…") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )

            Spacer(Modifier.height(20.dp))
            SectionTitle("Connection")
            OutlinedTextField(
                value = settings.serverUrl,
                onValueChange = { scope.launch { store.setServerUrl(it) } },
                label = { Text("Server URL") },
                placeholder = { Text("http://127.0.0.1:8787") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                "Axzora picks a working server automatically; set this to force a specific one.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(20.dp))
            SectionTitle("Family location sharing")
            Text("Everyone who wants to appear on each other's map installs Axzora and enters the SAME family code, then turns sharing on. Sharing is always opt-in.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            var testResult by remember { mutableStateOf("") }
            var testing by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            testResult = "Checking…"
                            Graph.configure(settings.serverUrl, settings.deviceId)
                            testResult = Graph.familySync.health()
                                .fold({ it }, { "Failed — ${it.message}" })
                            testing = false
                        }
                    },
                ) { Text("Check connection") }
                Spacer(Modifier.width(12.dp))
                if (testResult.isNotBlank()) {
                    Text(
                        testResult, fontSize = 12.sp,
                        color = if (testResult.startsWith("Connected")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.familyCode,
                onValueChange = { scope.launch { store.setFamilyCode(it.trim()) } },
                label = { Text("Family code") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(22.dp))
            SectionTitle("Assistant")
            Text(
                "Axzora answers from the phone first: what you have taught it, what it " +
                    "remembers, and what the phone itself knows. Install the on-device " +
                    "model and it can answer open questions too, with no internet and " +
                    "nothing leaving the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            AssistantModelCard(localLlm, settings, store)

            Spacer(Modifier.height(12.dp))
            LocalBrainServerCard()

            Spacer(Modifier.height(12.dp))
            ScreenLockCard()

            Spacer(Modifier.height(12.dp))
            AppShieldCard()

            Spacer(Modifier.height(24.dp))
            // The admin console is intentionally not listed here. Tap the
            // version line below SEVEN times quickly to open it.
            val adminTaps = remember { mutableIntStateOf(0) }
            Text(
                "Axzora OS · v1.0 · on-device assistant",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            adminTaps.intValue++
                            if (adminTaps.intValue >= 7) {
                                adminTaps.intValue = 0
                                onOpenAdmin()
                            } else {
                                scope.launch {
                                    delay(1500)
                                    adminTaps.intValue = 0
                                }
                            }
                        },
                        onLongClick = { /* reserved */ },
                    )
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/* ----------------------------- shared bits ----------------------------- */

@Composable
private fun TopRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground) }
        Text(title, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}


private fun isDefaultLauncher(context: Context): Boolean {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val res = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
    return res?.activityInfo?.packageName == context.packageName
}

/**
 * The intent that makes Axzora the default home screen.
 *
 * From Android 10 the role manager shows a clean in-app chooser; older
 * versions only offer the system's default-apps page, so the user picks
 * Axzora there. Nothing is uninstalled either way — the old launcher stays
 * one settings toggle away.
 *
 * Returned rather than launched, because the role chooser identifies the
 * requesting app from the *calling activity*. Started with a plain
 * startActivity it sees no caller, and rejects itself with "Package name
 * cannot be null" before the user is shown anything. It has to go through
 * an activity-result launcher.
 */
private fun defaultLauncherIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT >= 29) {
        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
            return rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
        }
    }
    return Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
}


@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
}

internal fun greeting(): String {
    val h = LocalDateTime.now().hour
    return when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Good night" }
}

/** Returns (time, date, am/pm) strings for the home-screen clock. */
internal fun nowText(): Triple<String, String, String> {
    val n = LocalDateTime.now()
    return Triple(
        n.format(DateTimeFormatter.ofPattern("h:mm")),
        n.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
        n.format(DateTimeFormatter.ofPattern("a")),
    )
}


/**
 * Install, load and remove the on-device model.
 *
 * Deliberately blunt about the size: half a gigabyte is a real cost on a
 * phone, and a download that surprises someone on mobile data is worse than
 * no feature at all.
 */
@Composable
private fun LocalBrainServerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var up by remember { mutableStateOf(com.axzora.os.assistant.LocalBrainServer.isUp()) }
    var msg by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    // Poll the server status while the screen is shown.
    LaunchedEffect(Unit) {
        while (true) {
            up = com.axzora.os.assistant.LocalBrainServer.isUp()
            delay(3000)
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Memory, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Offline brain (llama-server)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (up) "● Running on 127.0.0.1:8080 — Mr Happy works offline."
                        else "● Not running — tap Start to make Mr Happy work offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (up) Color(0xFF7CFC9C)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model path (optional)") },
                placeholder = { Text("storage/downloads/qwen2.5-1.5b-instruct-q8_0.gguf") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !up,
                onClick = {
                    scope.launch {
                        val path = model.ifBlank {
                            "storage/downloads/qwen2.5-1.5b-instruct-q8_0.gguf"
                        }
                        val ok = com.axzora.os.assistant.LocalBrainServer.start(context, path)
                        msg = if (ok) "Starting llama-server… give it ~20 s to load the model."
                        else "Couldn't start — is Termux installed with allow-external-apps=true?"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (up) "Running" else "Start offline brain") }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AssistantModelCard(
    llm: com.axzora.os.assistant.LocalLlm,
    settings: UserSettings,
    store: SettingsStore,
) {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(llm.installed) }
    var ready by remember { mutableStateOf(llm.ready) }
    var progress by remember { mutableStateOf(-1f) }
    var got by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }
    var modelUrl by remember { mutableStateOf(settings.modelUrl) }
    var hfToken by remember { mutableStateOf(settings.hfToken) }
    var localBrainUrl by remember { mutableStateOf(settings.localBrainUrl) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (ready) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "On-device model",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            ready -> "Loaded and answering, no internet needed"
                            installed -> "Installed (" + llm.sizeMb + "MB) - tap Load"
                            progress >= 0f -> "Downloading..."
                            else -> "Not installed - about 550MB"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (ready) {
                    Icon(
                        Icons.Rounded.CheckCircle, null,
                        tint = Color(0xFF22C55E), modifier = Modifier.size(19.dp),
                    )
                }
            }

            if (progress >= 0f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "" + (progress * 100).toInt() + "% - " + (got / 1_000_000) + "MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed && progress < 0f) {
                    Button(onClick = {
                        scope.launch {
                            progress = 0f
                            note = ""
                            store.setModelUrl(modelUrl); store.setHfToken(hfToken)
                            val err = llm.download(
                                modelUrl.ifBlank { com.axzora.os.assistant.LocalLlm.DEFAULT_MODEL_URL },
                                hfToken,
                            ) { p, done -> progress = p; got = done }
                            progress = -1f
                            if (err != null) {
                                note = err
                            } else {
                                installed = true
                                note = "Downloaded. Loading..."
                                ready = llm.load()
                                note = if (ready) "Ready - ask me anything, offline."
                                else llm.lastError.ifBlank { "Could not load the model" }
                            }
                        }
                    }) { Text("Download", fontSize = 12.sp) }
                }
                if (installed && !ready && progress < 0f) {
                    Button(onClick = {
                        scope.launch {
                            note = "Loading..."
                            ready = llm.load()
                            note = if (ready) "Ready." else llm.lastError
                        }
                    }) { Text("Load", fontSize = 12.sp) }
                }
                if (installed && progress < 0f) {
                    OutlinedButton(onClick = {
                        llm.delete()
                        installed = false
                        ready = false
                        note = "Removed. The offline engine still works."
                    }) { Text("Remove", fontSize = 12.sp) }
                }
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Wi-Fi is strongly advised - this is a large download. The model runs " +
                    "entirely on your phone afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )

            // Local brain on the home network (Ollama/OpenAI-compatible): the
            // assistant prefers this when reachable — private, free, no cloud.
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = localBrainUrl, onValueChange = { localBrainUrl = it },
                label = { Text("Local brain (LAN) URL") },
                placeholder = { Text("http://192.168.29.250:11434") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    TextButton(onClick = { scope.launch { store.setLocalBrainUrl(localBrainUrl) } }) {
                        Text("Save", fontSize = 12.sp)
                    }
                },
            )
            Text(
                "Point this at a computer on your Wi-Fi running Ollama (or any " +
                    "OpenAI-compatible server). When it answers, the assistant uses it " +
                    "instead of the cloud. Blank = cloud only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 6.dp),
            )

            if (!ready && progress < 0f) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = modelUrl, onValueChange = { modelUrl = it },
                    label = { Text("Model URL (optional)") },
                    placeholder = { Text("Blank = default Gemma") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hfToken, onValueChange = { hfToken = it },
                    label = { Text("HuggingFace token") },
                    placeholder = { Text("hf_… (for gated models)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The default Gemma model is gated — get a free token at " +
                        "huggingface.co/settings/tokens (accept the model's licence first), " +
                        "or paste a public .task model URL above and leave the token blank.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}


/**
 * Grants the assistant the right to lock the screen.
 *
 * Android reserves screen locking for device admins, so this opens the
 * system's own approval screen. The policy Axzora declares is force-lock and
 * nothing else — it cannot wipe the phone, read it, or restrict it.
 */
@Composable
private fun ScreenLockCard() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(com.axzora.os.assistant.DeviceControl(context).canLock)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { granted = com.axzora.os.assistant.DeviceControl(context).canLock }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Lock, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Let the assistant lock the screen",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (granted) "Say \"lock the phone\" and it will."
                        else "Android only allows this for an app you approve.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = granted,
                    onCheckedChange = { want ->
                        val dc = com.axzora.os.assistant.DeviceControl(context)
                        if (want) {
                            launcher.launch(dc.requestLockPermission())
                        } else {
                            dc.removeLockPermission()
                            granted = false
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlocking is not possible for any app, by design - if an assistant " +
                    "could unlock a phone on request, so could anyone holding it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

/* App Shield: hide dev/debug/accessibility settings around a locked-down app. */
@Composable
private fun AppShieldCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canWrite by remember { mutableStateOf(AppShield.canWrite(context)) }
    var msg by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    val active = remember { mutableStateOf(AppShield.activeShield(context)) }

    // Load the installed-app list once, and refresh permission state on show.
    LaunchedEffect(Unit) {
        apps = runCatching { AppRepository(context).loadApps() }.getOrDefault(emptyList())
        canWrite = AppShield.canWrite(context)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Shield, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "App Shield (open banking apps cleanly)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (canWrite) "Pick an app → settings hide → launch → Revert."
                        else "Needs WRITE_SECURE_SETTINGS (grant once via ADB or Shizuku).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            // Active shield (Revert the one in flight)
            active.value?.let { a ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Shielded: ${a.label}", fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            scope.launch {
                                msg = AppShield.revertActive(context).ifBlank { "Reverted." }
                                active.value = null
                            }
                        }) { Text("Revert") }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (!canWrite) {
                Text(
                    "Grant once:\n${AppShield.GRANT_COMMAND}",
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }

            // App picker (Shield & Launch per app)
            if (canWrite) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (expanded) "Hide apps" else "Pick an app to shield…") }

                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    apps.take(20).forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val r = AppShield.shieldAndLaunch(context, app.packageName, app.label)
                                    msg = r.ifBlank {
                                        postShieldNotification(context, app.label)
                                        active.value = AppShield.activeShield(context)
                                        "Shielded ${app.label} — launched. Revert when done."
                                    }
                                }
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(app.emojiOr("📱"), fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(app.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("Shield", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (msg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun AppInfo.emojiOr(fallback: String): String = fallback

/** A persistent "Revert" notification so the user can restore settings after the app closes. */
private fun postShieldNotification(context: Context, label: String) {
    try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    "axzora_shield", "App Shield",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val revertIntent = android.content.Intent(context, MainActivity::class.java)
            .setAction("axzora.shield.revert")
        val pi = android.app.PendingIntent.getActivity(
            context, 200, revertIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, "axzora_shield")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("App Shield active")
            .setContentText("$label is shielded. Tap Revert to restore settings.")
            .setContentIntent(pi)
            .addAction(0, "Revert", pi)
            .setOngoing(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context).notify(200, n)
    } catch (e: Throwable) { /* notification is best-effort */ }
}



/**
 * Holds the display awake while [on] is true.
 *
 * Uses the window flag rather than a wake lock: the flag is released
 * automatically if the app is killed, so a crash can never leave a phone
 * with its screen stuck on until the battery dies.
 */
@Composable
private fun KeepScreenAwake(on: Boolean) {
    val context = LocalContext.current
    DisposableEffect(on) {
        val window = (context as? android.app.Activity)?.window
        if (on) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}


/**
 * Offers a downloaded-and-install update.
 *
 * Kept simple on purpose: one screen, a progress bar, and the honest note
 * that Android will ask permission the first time. Nothing installs behind
 * the user's back.
 */
@Composable
private fun UpdateDialog(
    available: com.axzora.os.update.Updater.Available,
    updater: com.axzora.os.update.Updater,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(-1f) }
    var note by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = { if (progress < 0f) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Bolt, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Update available",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Axzora " + available.versionName + " is ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                if (available.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        available.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }

                if (progress in 0f..1f) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "" + (progress * 100).toInt() + "%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }

                if (note.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (progress < 0f) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("Later")
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                progress = 0f
                                note = ""
                                val err = updater.downloadAndInstall { progress = it }
                                if (err != null) { note = err; progress = -1f }
                            }
                        },
                        enabled = progress < 0f,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (progress < 0f) "Update now" else "Downloading…") }
                }
            }
        }
    }
}
