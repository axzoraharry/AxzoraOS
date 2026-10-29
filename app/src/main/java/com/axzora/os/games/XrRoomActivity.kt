package com.axzora.os.games

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity

/**
 * Satyug XR Room v0 — one 3D room, one avatar (Aria), one command line that
 * changes the world (assets/xr_room.html, A-Frame/WebXR). The page exposes
 * window.satyugCommand(text) — the seam Mr Happy (the AI Game Master) drives.
 * Needs internet on first load (A-Frame is a CDN script for v0).
 */
class XrRoomActivity : ComponentActivity() {
    private lateinit var web: WebView
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0F16")
        window.navigationBarColor = Color.parseColor("#0B0F16")
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F16"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // let A-Frame/WebXR + the mic (Web Speech) work
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(req: PermissionRequest) { req.grant(req.resources) }
            }
            loadUrl("file:///android_asset/xr/index.html")
        }
        setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    /** Mr Happy hook (future): change the world by voice from the assistant. */
    fun command(text: String) = web.evaluateJavascript("window.satyugCommand(${org.json.JSONObject.quote(text)})", null)
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
