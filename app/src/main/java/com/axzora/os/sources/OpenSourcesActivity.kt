package com.axzora.os.sources

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * "Open Sources" — Colibri / p.codekk.com brought in as an app of AxzoraOS.
 *
 * It's the live hub (AI Daily Digest, dev tools, and every open-source project
 * the owner curates), so it always shows the full, current set of sources with
 * nothing to sync — add a source on the site and it's here. Opened from the
 * home tile, the drawer, or by voice ("open sources").
 */
class OpenSourcesActivity : ComponentActivity() {

    companion object {
        const val BASE = "https://p.codekk.com/"
        const val EXTRA_PATH = "axzora.sources.path"
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0F16")
        window.navigationBarColor = Color.parseColor("#0B0F16")

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F16")) }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F16"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = WebViewClient()   // keep navigation inside the app
        }
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        web.loadUrl(target())
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        web.loadUrl(target())
    }

    /** BASE, or a specific section when Mr Happy asks (e.g. "ai", "json"). */
    private fun target(): String {
        val p = intent?.getStringExtra(EXTRA_PATH)?.trim()?.trim('/') ?: ""
        return if (p.isEmpty()) BASE else BASE + p
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
