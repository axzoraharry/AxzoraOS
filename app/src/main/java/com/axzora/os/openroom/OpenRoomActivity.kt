package com.axzora.os.openroom

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.flow.first
import kotlin.concurrent.thread

/**
 * OpenRoom as a first-class app of AxzoraOS: full-screen, its own launcher
 * tile, opens instantly when the server is up — and when it isn't, starts it
 * through Termux and waits, instead of showing a dead page.
 */
class OpenRoomActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATH = "axzora.openroom.path"
        /** Bump with each frontend build so the WebView cache clears once. */
        const val BUNDLE_VER = 26
        @Volatile
        var OpenRoomActivityContext: com.axzora.os.openroom.OpenRoomActivity? = null
    }

    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var launcherBtn: Button
    private val ui = Handler(Looper.getMainLooper())
    private var startedServer = false
    // The host serving OpenRoom. Defaults to on-device loopback; when OpenRoom
    // runs on the companion computer, this becomes that machine's LAN address
    // (read from the app's server URL, which already points at the computer).
    private var host: String = "127.0.0.1"
    /** Candidate hosts serving OpenRoom — tried in order, so an IP change
     *  (Wi-Fi ↔ mobile data on the backend machine) never breaks reachability. */
    private var hosts: List<String> = listOf("127.0.0.1")
    private var lastJsError: String? = null

    // ---- Screen capture (real MediaProjection) via the Axzora bridge ---------
    // Launches the system "Start capturing" consent, then hands the result to
    // ScreenCapture which writes frames to a PNG the :8002 bridge serves.
    private val screenCaptureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                com.axzora.os.godseye.ScreenCapture.start(this, result.resultCode, result.data!!)
                runOnUiThread {
                    if (::web.isInitialized) web.evaluateJavascript("window.AxzoraOnScreenCapture && window.AxzoraOnScreenCapture(true)", null)
                }
            } else {
                runOnUiThread {
                    if (::web.isInitialized) web.evaluateJavascript("window.AxzoraOnScreenCapture && window.AxzoraOnScreenCapture(false)", null)
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenRoomActivityContext = this
        window.statusBarColor = Color.parseColor("#0B0F16")
        window.navigationBarColor = Color.parseColor("#0B0F16")

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F16")) }

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F16"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.textZoom = 100
            settings.loadsImagesAutomatically = true
            settings.setGeolocationEnabled(false)
            // Paint as one surface, edge to edge, and keep scrolling buttery on a
            // phone WebView (no over-scroll glow, no flashing scrollbars).
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webChromeClient = object : WebChromeClient() {
                // Vite dev keeps "page finished" pending on dynamic imports, so a
                // bare onPageFinished often never fires — reveal the page once it's
                // substantially loaded instead of sitting on "Opening…" forever.
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    // Reveal very early (10%) so OpenRoom feels instant — the page
                    // is interactive well before it has finished every lazy chunk.
                    if (newProgress >= 10) hideOverlay()
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        lastJsError = m.message()
                    }
                    return true
                }
                // Grant the WebView camera/mic so the Satyug Super App biometric + voice features work inside OpenRoom.
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    val ok = arrayOf(
                        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                        android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                    )
                    val wanted = request.resources.filter { it in ok }
                    request.grant(if (wanted.isEmpty()) request.resources else wanted.toTypedArray())
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    ui.postDelayed({
                        view.evaluateJavascript(
                            "document.getElementById('root').childElementCount + ''",
                        ) { result ->
                            if (result == "0" || result == "null") {
                                showError("Loaded but the app didn't start." +
                                    (if (lastJsError != null) "\nError: $lastJsError" else ""))
                            }
                        }
                    }, 250)
                }
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        showError("Couldn't load ${view.url}\n${error.description}")
                    }
                }
            }
            // JS bridge so OpenRoom's web "bridge" apps can open AxzoraOS's
            // real native screens (Wallet, Mr Happy, Devices, AI Core) — the
            // way we "connect everything" inside the OpenRoom frontend, cleanly:
            // a web page calls Axzora.openWallet() and the native app launches.
            addJavascriptInterface(AxzoraBridge(), "Axzora")
        }
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F16"))
            val pad = dp(32)
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = "OpenRoom"
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            addView(ProgressBar(context).apply {
                val lp = LinearLayout.LayoutParams(dp(36), dp(36)); lp.topMargin = dp(20); layoutParams = lp
            })
            status = TextView(context).apply {
                textSize = 15f
                setTextColor(Color.parseColor("#B8C2D0"))
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ); lp.topMargin = dp(16); layoutParams = lp
            }
            addView(status)
            retry = Button(context).apply {
                text = "Try again"
                visibility = View.GONE
                setOnClickListener { startedServer = false; load() }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ); lp.topMargin = dp(18); layoutParams = lp
            }
            addView(retry)
            launcherBtn = Button(context).apply {
                text = "Open AxzoraOS launcher"
                visibility = View.GONE
                setOnClickListener { openNativeLauncher() }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ); lp.topMargin = dp(8); layoutParams = lp
            }
            addView(launcherBtn)
        }
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        // Resolve the machine(s) serving OpenRoom. The app's server URL usually
        // gives the right host, but the backend machine's IP can change (Wi-Fi ↔
        // mobile data), so probe a list and use whichever answers. That keeps
        // OpenRoom reachable even when the saved address points at an old IP.
        hosts = buildHostList()
        host = hosts.first()
        load()
    }

    /** A small ordered list of hosts that might serve OpenRoom, deduped. */
    private fun buildHostList(): List<String> {
        val list = mutableListOf<String>()
        val serverHost = runCatching {
            val url = kotlinx.coroutines.runBlocking {
                com.axzora.os.data.SettingsStore(this@OpenRoomActivity).settings.first().serverUrl
            }
            android.net.Uri.parse(url).host?.takeIf { it.isNotBlank() && it != "localhost" }
        }.getOrNull()
        if (serverHost != null) list.add(serverHost)
        com.axzora.os.Config.SERVER_CANDIDATES.forEach { u ->
            val h = android.net.Uri.parse(u).host ?: return@forEach
            if (h.isNotBlank() && !list.contains(h)) list.add(h)
        }
        if (list.isEmpty()) list.add("127.0.0.1")
        return list
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (OpenRoom.isUp(host)) web.loadUrl(targetUrl()) else load()
    }

    private fun load() {
        // Bust the WebView cache ONLY when the frontend bundle version changes
        // (a one-time clear so a fresh build is picked up). A per-load
        // clearCache made every open re-download the whole bundle, which is why
        // it felt "too slow" until you reopened. Bump BUNDLE_VER with each build.
        try {
            val prefs = getSharedPreferences("axzora_openroom", MODE_PRIVATE)
            val have = prefs.getInt("bundle_ver", 0)
            if (have != BUNDLE_VER) {
                prefs.edit().putInt("bundle_ver", BUNDLE_VER).apply()
                web.clearCache(true)
            }
        } catch (_: Throwable) { }
        overlay.visibility = View.VISIBLE
        retry.visibility = View.GONE
        launcherBtn.visibility = View.GONE
        status.text = "Opening " + targetUrl() + "…"
        thread {
            val active = hosts.firstOrNull { OpenRoom.isUp(it) }
            ui.post {
                if (active != null) {
                    host = active
                    web.loadUrl(targetUrl())
                } else {
                    host = hosts.first()
                    waitForServer()
                }
            }
        }
    }

    /** Server not answering: wait for the watchdog to bring it up, then connect. */
    private fun waitForServer() {
        overlay.visibility = View.VISIBLE
        // The server runs on the machine hosting OpenRoom. Only a device that IS
        // that machine (loopback) can start it locally; a remote phone just waits
        // for the watchdog to restart it, then connects.
        val localHost = host == "127.0.0.1" || host.isBlank() || host == "localhost"
        if (localHost && !startedServer) {
            startedServer = true
            val ok = OpenRoom.start(this)
            status.text = if (ok) "Starting OpenRoom on this phone…\n(first start ~20 s)"
            else "Connecting to OpenRoom…\n(waiting for the server…)"
        } else {
            status.text = "Connecting to OpenRoom…\n(waiting for the server…)"
        }
        var tries = 0
        lateinit var poll: Runnable
        poll = Runnable {
            thread {
                val active = hosts.firstOrNull { OpenRoom.isUp(it) }
                ui.post {
                    when {
                        active != null -> {
                            host = active
                            web.loadUrl(targetUrl())
                        }
                        ++tries < 300 -> ui.postDelayed(poll, 1000)
                        else -> {
                            status.text = "OpenRoom is taking a while.\nMake sure the server is running on ${hosts.first()}."
                            retry.visibility = View.VISIBLE
                            launcherBtn.visibility = View.VISIBLE
                            overlay.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
        ui.postDelayed(poll, 1200)
    }

    /** The URL to open — the desktop, or a specific app when Mr Happy asks. */
    private fun targetUrl(): String = OpenRoom.url(host, intent?.getStringExtra(EXTRA_PATH) ?: "")

    /** Escape hatch: jump to the native AxzoraOS launcher (always on-device). */
    private fun openNativeLauncher() {
        runCatching {
            startActivity(
                Intent(this@OpenRoomActivity, com.axzora.os.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("axzora.screen", "HOME"),
            )
        }
    }

    private fun hideOverlay() { overlay.visibility = View.GONE }

    /** Show a load/render problem in the status line, with a retry button. */
    private fun showError(msg: String) {
        ui.post {
            overlay.visibility = View.VISIBLE
            status.text = msg
            retry.visibility = View.VISIBLE
            launcherBtn.visibility = View.VISIBLE
        }
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    /**
     * JS bridge exposed to the OpenRoom web frontend as `window.Axzora`.
     * A bridge "app" in OpenRoom (e.g. Wallet, Mr Happy, Devices) calls these to
     * open the real AxzoraOS native screen — so everything is reachable from the
     * OpenRoom desktop without overlay hacks.
     */
    /** Launch the system "Start capturing" consent then start MediaProjection. */
    fun launchScreenCapture() {
        try {
            val mm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            screenCaptureLauncher.launch(mm.createScreenCaptureIntent())
        } catch (e: Exception) { /* ignore */ }
    }

    @SuppressLint("unused")
    internal class AxzoraBridge {

        @JavascriptInterface fun openWallet() {
            bridgeStart(com.axzora.os.MainActivity::class.java, "WALLET")
        }

        @JavascriptInterface fun openMrHappy() {
            bridgeStart(com.axzora.happyreddy.MainActivity::class.java, "")
        }

        @JavascriptInterface fun openDevices() {
            bridgeStart(com.axzora.os.MainActivity::class.java, "DEVICES")
        }

        @JavascriptInterface fun openAgi() {
            bridgeStart(com.axzora.os.MainActivity::class.java, "AGI")
        }

        /**
         * Open any AxzoraOS screen by its Screen-enum name (WALLET, VOICE,
         * MESSAGES, ACCESSIBILITY, SOUNDMESH, PEOPLE, NEARBY, SETTINGS …). This
         * is what lets the OpenRoom Hub expose every feature as a home tile
         * without needing one function per screen. Unknown names fall back to
         * the launcher home.
         */
        @JavascriptInterface fun openScreen(name: String) {
            bridgeStart(com.axzora.os.MainActivity::class.java, name.ifBlank { "HOME" })
        }

        /** Open the built-in AxzoraOS launcher (app grid + quick chips). */
        @JavascriptInterface fun openLauncher() {
            bridgeStart(com.axzora.os.MainActivity::class.java, "HOME")
        }

        /**
         * Open a http(s) URL in the phone's external browser (ACTION_VIEW), so the
         * OpenRoom web apps can let the user jump to a real browser — useful for
         * sites that block iframing or to open the OpenRoom UI in a full browser.
         * Returns a short status string.
         */
        @JavascriptInterface fun openUrl(url: String): String {
            val u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) return "not a web URL"
            return try {
                val ctx = OpenRoomActivityContext ?: return "no context"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "opened $u"
            } catch (e: Exception) {
                "could not open: ${e.message}"
            }
        }

        /** Whether the on-device accessibility service (Mr Happy's hands) is on. */
        @JavascriptInterface fun a11yEnabled(): Boolean {
            return com.axzora.happyreddy.HappyReddyA11y.get() != null
        }

        /** Request the system "Start capturing" consent, then start MediaProjection. */
        @JavascriptInterface fun requestScreenCapture() {
            val ctx = OpenRoomActivityContext ?: return
            ctx.runOnUiThread { ctx.launchScreenCapture() }
        }

        /** Whether screen capture is running. */
        @JavascriptInterface fun screenCaptureActive(): Boolean {
            return com.axzora.os.godseye.ScreenCapture.isRunning()
        }

        /** Path of the latest captured frame PNG (or a marker if not captured). */
        @JavascriptInterface fun screenFramePath(): String {
            return com.axzora.os.godseye.ScreenCapture.framePath()
        }

        /**
         * The device's Wallet snapshot, so the OpenRoom Wallet window can show a
         * real balance and recent movements. Reads the on-device WalletStore
         * (instant, works offline); returns JSON {balance, uid, txns:[…]}.
         */
        @JavascriptInterface fun walletSnapshot(): String {
            return try {
                val ctx = OpenRoomActivityContext ?: return "{}"
                val st = kotlinx.coroutines.runBlocking {
                    com.axzora.os.wallet.WalletStore(ctx).state.first()
                }
                val txns = org.json.JSONArray()
                st.txns.take(12).forEach { t ->
                    txns.put(
                        org.json.JSONObject()
                            .put("hp", t.hp)
                            .put("party", t.party)
                            .put("note", t.note)
                            .put("ts", t.ts),
                    )
                }
                org.json.JSONObject()
                    .put("balance", st.balanceHp)
                    .put("uid", st.uid)
                    .put("txns", txns)
                    .toString()
            } catch (_: Throwable) { "{}" }
        }

        /**
         * The phone's real launchable apps, so the OpenRoom desktop can offer
         * them as windows. Returns a JSON array of [{label, pkg}, …].
         */
        @JavascriptInterface fun listAndroidApps(): String {
            val ctx = OpenRoomActivityContext ?: return "[]"
            return try {
                val pm = ctx.packageManager
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(intent, 0)
                val seen = mutableSetOf<String>()
                val arr = org.json.JSONArray()
                apps.take(80).forEach { ri ->
                    val pkg = ri.activityInfo.packageName
                    val label = ri.loadLabel(pm).toString()
                    if (seen.add(pkg)) {
                        arr.put(org.json.JSONObject().put("label", label).put("pkg", pkg))
                    }
                }
                arr.toString()
            } catch (_: Throwable) { "[]" }
        }

        /** Launch an installed app by package name (on the main thread). */
        @JavascriptInterface fun launchApp(packageName: String) {
            val ctx = OpenRoomActivityContext ?: return
            try {
                val launch = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            } catch (_: Throwable) { }
        }

        /**
         * Drive the currently focused screen (e.g. a web app window) through the
         * accessibility service, so Mr Happy can type into / tap / read any app.
         * action: "type" | "tap" | "find_tap" | "read".
         */
        @JavascriptInterface fun controlScreen(action: String, arg: String): String {
            val svc = com.axzora.happyreddy.HappyReddyA11y.get()
                ?: return "Accessibility is off — enable Mr Happy's accessibility first."
            return try {
                when (action) {
                    "type" -> svc.typeText(arg)
                    "tap" -> svc.tapText(arg)
                    "find_tap" -> svc.scrollToAndTap(arg)
                    "read" -> svc.readScreen()
                    else -> "Unknown action: $action"
                }
            } catch (e: Throwable) { "Error: ${e.message}" }
        }

        /**
         * The apps you most recently opened, resolved to labels — so the OpenRoom
         * desk can surface Mr Happy's "smart" picks ahead of everything else.
         */
        @JavascriptInterface fun suggestedApps(): String {
            val ctx = OpenRoomActivityContext ?: return "[]"
            return try {
                val prefs = ctx.getSharedPreferences("axzora_recent", android.content.Context.MODE_PRIVATE)
                val recent = prefs.getString("recent_packages", "")
                    ?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
                val pm = ctx.packageManager
                val arr = org.json.JSONArray()
                recent.take(6).forEach { pkg ->
                    runCatching {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        arr.put(org.json.JSONObject().put("label", pm.getApplicationLabel(ai).toString()).put("pkg", pkg))
                    }
                }
                arr.toString()
            } catch (_: Throwable) { "[]" }
        }

        private fun bridgeStart(cls: Class<*>, screen: String) {
            try {
                val a = OpenRoomActivityContext ?: return
                val i = Intent(a, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (screen.isNotBlank()) i.putExtra("axzora.screen", screen)
                a.startActivity(i)
            } catch (_: Throwable) { }
        }
    }
}
