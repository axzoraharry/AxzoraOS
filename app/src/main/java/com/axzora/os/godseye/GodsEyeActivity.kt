package com.axzora.os.godseye

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat

/**
 * God's Eye View as a first-class, in-app screen.
 *
 * Loads the on-device God's Eye View OSINT globe (:4173) in a full-screen WebView
 * instead of opening the phone's browser, so it stays inside AxzoraOS, is
 * hardware-accelerated and smooth, supports pinch-zoom, and lets the globe use the
 * phone's real GPS via WebView geolocation ("fly to my location").
 *
 * The globe's data (aircraft/ships/satellites/earthquakes/cameras) is real public
 * signal; this screen just hosts it and grants the browser the two capabilities it
 * needs: location and media.
 */
class GodsEyeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "axzora.godseye.url"
        const val DEFAULT_URL = "http://127.0.0.1:4173/"
    }

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0F16")
        window.navigationBarColor = Color.parseColor("#0B0F16")

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F16")) }
        setContentView(root)

        val url = intent?.getStringExtra(EXTRA_URL) ?: DEFAULT_URL

        // Progress + error overlays (drawn over the WebView, hint-style).
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
        }
        val progressParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (10 * resources.displayMetrics.density).toInt(),
        ).apply { gravity = android.view.Gravity.TOP }
        root.addView(progress, progressParams)

        errView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#9BB8D8"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        root.addView(errView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F16"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            settings.textZoom = 100
            // Geolocation so the globe can fly to the user's real position.
            settings.setGeolocationEnabled(true)
            // Pinch-to-zoom on the globe.
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(false)
            // Smooth: no over-scroll glow, no flashing scrollbars, hardware accel.
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }

        // Assign the clients outside the apply block so the anonymous classes
        // reliably capture the Activity's lateinit fields/properties.
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    if (progress.visibility != View.VISIBLE) progress.visibility = View.VISIBLE
                    progress.progress = newProgress
                } else {
                    progress.visibility = View.GONE
                    errView.visibility = View.GONE
                }
            }

            // Grant the WebView location so the globe can centre on the phone.
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback,
            ) {
                // In this app the globe is trusted, so grant the WebView fix
                // directly. The Android runtime permission is requested below.
                callback.invoke(origin, true, false)
            }

            // Also grant media (mic/cam) so any globe voice/live-cam features work.
            override fun onPermissionRequest(request: PermissionRequest) {
                val ok = arrayOf(
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                )
                val wanted = request.resources.filter { it in ok }
                request.grant(if (wanted.isEmpty()) request.resources else wanted.toTypedArray())
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    errView.visibility = View.VISIBLE
                    errView.text = "Couldn't load God's Eye View\n${error.description}\n\n" +
                        "Make sure the on-device globe server (:4173) is running."
                }
            }
        }
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Back closes this screen and returns to the AxzoraOS home.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        // Ask for the Android runtime location permission so the globe can fly to
        // the user. Needed for WebView geolocation on Android 6+.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                1287,
            )
        }

        web.loadUrl(url)
    }

    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
