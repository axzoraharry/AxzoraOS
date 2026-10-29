package com.axzora.os.games

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.axzora.os.Config
import com.axzora.os.data.SettingsStore
import com.axzora.os.wallet.Rail
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Axzora Ludo. Two modes, chosen by [EXTRA_MODE]:
 *
 *  - "ai"     : offline board vs three CPUs. Always free.
 *  - "online" : real-time multiplayer over the backend's WebSocket endpoint
 *               (/ludo). Any two AxzoraOS phones can play — a 4-letter room
 *               code or Quick Match, up to 8 in a room (4 play, others watch
 *               and are seated next round). A dropped player keeps their seat
 *               for two minutes and can get back in.
 *
 * Happy Paisa is settled here, driven by the board: nothing is debited until a
 * staked game actually STARTS (escrow), and the winner credits the whole pot.
 * The server tracks paid/settled per seat so a reconnect never double-charges.
 */
class LudoActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "axzora.game.mode"     // "online" | "ai"
        const val EXTRA_STAKE = "axzora.game.stake"   // Happy Paisa per player (online only)
        const val EXTRA_RESUME = "axzora.game.resume" // jump straight back into the held seat
    }

    private lateinit var web: WebView
    private val wallet by lazy { WalletStore(this) }
    private val settings by lazy { SettingsStore(this) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0F16")
        window.navigationBarColor = Color.parseColor("#0B0F16")

        val mode = intent.getStringExtra(EXTRA_MODE) ?: "ai"
        val stake = intent.getIntExtra(EXTRA_STAKE, 0)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)

        // Voice lines ("Your turn", "Six!", "Dobbindi haha"). Best effort: if
        // the phone has no TTS engine the board just plays its chimes.
        tts = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) tts?.setSpeechRate(1.05f)
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F16"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "AxzoraGame")
            val url = when (mode) {
                "online" -> "file:///android_asset/ludo.html?mode=online&stake=$stake" + (if (resume) "&resume=1" else "")
                "pass" -> "file:///android_asset/ludo.html?mode=pass"
                else -> "file:///android_asset/ludo.html?mode=ai"
            }
            loadUrl(url)
        }
        setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Back button: let the page show its "Leave the game?" dialog (it keeps
        // the seat held). If the page has nothing to show, just finish.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!::web.isInitialized) { finish(); return }
                web.evaluateJavascript("(function(){try{return !!(window.onBack&&onBack())}catch(e){return false}})()") { r ->
                    if (r == null || r.trim() != "true") finish()
                }
            }
        })
    }

    /** wss://<server>/ludo, derived from the app's server URL (with the usual
     *  launch-time fallback: serverUrl can be blank for a few seconds after a
     *  cold start, so fall back to the built-in default). */
    private fun wsUrl(): String {
        val base = runCatching { runBlocking { settings.settings.first().serverUrl } }
            .getOrDefault("").ifBlank { Config.DEFAULT_SERVER }
        return base.trim().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ludo"
    }

    /** The name friends see: the profile name, else the phone's device name,
     *  else the model. */
    private fun playerName(): String {
        val profile = runCatching { runBlocking { settings.settings.first().displayName } }.getOrDefault("")
        if (profile.isNotBlank()) return profile.trim().take(16)
        val dev = runCatching { AndroidSettings.Global.getString(contentResolver, "device_name") }.getOrNull()
        return (dev ?: Build.MODEL ?: "Player").take(16)
    }

    private fun buzz(ms: Long) {
        if (ms <= 0) return
        runCatching {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun wsUrl(): String = this@LudoActivity.wsUrl()
        @JavascriptInterface fun name(): String = playerName()

        /** Stable identity for resuming a seat: the wallet id. */
        @JavascriptInterface fun uid(): String = runCatching { runBlocking { wallet.ensureUid() } }.getOrDefault("")

        @JavascriptInterface fun balance(): String {
            val b = runBlocking { wallet.currentBalance() }
            return if (b == b.toLong().toDouble()) b.toLong().toString() else String.format("%.2f", b)
        }

        /** Debit the stake when a staked game starts. "ok" or a reason string. */
        @JavascriptInterface fun escrow(stake: Int): String {
            if (stake <= 0) return "ok"
            val ok = runBlocking { wallet.debit(stake.toDouble(), "Ludo", "Ludo match stake", Rail.SYSTEM) }
            return if (ok) "ok" else "Not enough Happy Paisa for a $stake ₴ stake"
        }

        /** Winner credits the whole pot. */
        @JavascriptInterface fun settle(pot: Int) {
            if (pot <= 0) return
            runBlocking { wallet.credit(pot.toDouble(), "Ludo", "Won the Ludo pot", Rail.SYSTEM) }
            runOnUiThread { Toast.makeText(this@LudoActivity, "You won $pot ₴ Happy Paisa!", Toast.LENGTH_LONG).show() }
        }

        /** Speak a short line. lang: "en", "te" (Telugu), "hi"… falls back to English. */
        @JavascriptInterface fun say(text: String, lang: String) {
            val t = tts ?: return
            if (!ttsReady) return
            runOnUiThread {
                val loc = when (lang.lowercase()) { "te" -> Locale("te", "IN"); "hi" -> Locale("hi", "IN"); "kn" -> Locale("kn", "IN"); else -> Locale.US }
                val res = t.setLanguage(loc)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
                t.speak(text.take(80), TextToSpeech.QUEUE_FLUSH, null, "ludo")
            }
        }

        @JavascriptInterface fun vibrate(ms: Int) = buzz(ms.toLong())

        /** A name chosen on the board becomes the AxzoraOS profile name too. */
        @JavascriptInterface fun setName(name: String) {
            val n = name.trim().take(16)
            if (n.isNotEmpty()) runCatching { runBlocking { settings.setDisplayName(n) } }
        }

        @JavascriptInterface fun finish() { runOnUiThread { this@LudoActivity.finish() } }

        // legacy hooks (harmless)
        @JavascriptInterface fun stake(): Int = 0
        @JavascriptInterface fun onWin(winner: String) {}
    }

    override fun onDestroy() {
        runCatching { tts?.stop(); tts?.shutdown() }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
