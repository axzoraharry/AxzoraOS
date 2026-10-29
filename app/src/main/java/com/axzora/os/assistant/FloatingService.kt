package com.axzora.os.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.axzora.os.MainActivity
import com.axzora.os.data.SettingsStore
import com.axzora.os.launcher.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The Axzora bubble: a small draggable button that floats over whatever app is
 * in front, and opens the assistant without leaving it.
 *
 * Compose normally relies on an Activity to supply lifecycle, saved-state and
 * view-model owners. A window added straight to [WindowManager] has none, so
 * this service acts as all three — without that the ComposeView crashes the
 * moment it tries to compose.
 */
class FloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private lateinit var windowManager: WindowManager
    private var bubble: ComposeView? = null
    private var panel: ComposeView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: CommandEngine

    private val expanded = mutableStateOf(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        engine = CommandEngine(applicationContext, AppRepository(applicationContext))

        scope.launch {
            // The assistant needs the app list to resolve "open …", and the
            // user's name so it can address them.
            val settings = runCatching { SettingsStore(applicationContext).settings.first() }.getOrNull()
            engine.userName = settings?.displayName.orEmpty()
            engine.apps = AppRepository(applicationContext).loadApps()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Without the overlay permission the window silently fails to attach,
        // so check before trying and send the user to grant it.
        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, notification())
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (bubble == null) showBubble()
        return START_STICKY
    }

    // ---------------------------------------------------------------- windows

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun prepare(view: ComposeView) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
    }

    private fun showBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Right edge, a little above centre — out of the way of headings
            // and status bar, and where people expect a floating button.
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = resources.displayMetrics.heightPixels * 42 / 100
        }

        val view = ComposeView(this).apply {
            prepare(this)
            setContent { FloatingBubble(onTap = { toggle() }) }
        }

        // Drag with a small movement threshold, so a tap that wobbles a couple
        // of pixels still registers as a tap rather than a drag.
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false

        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > 16 || abs(dy) > 16)) dragging = true
                    if (dragging) {
                        // Gravity is END, so x grows leftwards from the edge.
                        params.x = (startX - dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) v.performClick()
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(view, params) }
        bubble = view
    }

    private fun toggle() {
        // The bubble fronts Mr Happy when it's on the phone — that is the
        // assistant of record. The built-in panel remains the fallback so a
        // phone without it still gets an answer, not a dead button.
        if (!expanded.value && launchMrHappy()) return
        if (expanded.value) collapse() else expand()
    }

    private fun launchMrHappy(): Boolean {
        // Embedded now - Mr Happy ships inside Axzora, so this is a plain
        // internal activity start, present on every install.
        val intent = Intent(this, com.axzora.happyreddy.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    private fun expand() {
        if (panel != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Focusable so the keyboard works; the panel is dismissed by its
            // own close button or by tapping the bubble again.
            0,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }

        val view = ComposeView(this).apply {
            prepare(this)
            setContent {
                FloatingPanel(
                    engine = engine,
                    onClose = { collapse() },
                    onOpenApp = {
                        collapse()
                        runCatching {
                            startActivity(
                                Intent(this@FloatingService, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
        }
        runCatching { windowManager.addView(view, params) }
        panel = view
        expanded.value = true
    }

    private fun collapse() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        expanded.value = false
    }

    // ---------------------------------------------------------------- service

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Assistant bubble", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Keeps the Axzora bubble on screen" }
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            // Ember accent, so the notification shade matches the theme.
            .setColor(0xFFF0A73C.toInt())
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Axzora bubble")
            .setContentText("Tap the bubble anywhere to ask something.")
            .addAction(Notification.Action.Builder(null, "Hide", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        collapse()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "axzora_bubble"
        private const val NOTIF_ID = 77
        const val ACTION_STOP = "com.axzora.os.STOP_BUBBLE"

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, FloatingService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FloatingService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        /** Opens the system page where overlay access is granted. */
        fun requestPermission(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
