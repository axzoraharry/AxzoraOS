package com.axzora.os.games

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.runBlocking

/**
 * The Games section of AxzoraOS (assets/games.html). Lists games — free and
 * Happy-Paisa-staked — and shows the wallet balance.
 *
 * Ludo has two entries: "Play with friends" launches real-time online
 * multiplayer (the board handles rooms + the Happy Paisa pot itself, debiting
 * only when a staked game actually starts), and "vs AI" launches the free
 * offline board. No Firebase, no setup — online play rides on the backend the
 * phone already uses.
 */
class GamesActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val wallet by lazy { WalletStore(this) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0F16")
        window.navigationBarColor = Color.parseColor("#0B0F16")
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F16"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "AxzoraGames")
            loadUrl("file:///android_asset/games.html")
        }
        setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.refresh&&refresh()", null)
    }

    private inner class Bridge {
        @JavascriptInterface fun balance(): String {
            val b = runBlocking { wallet.currentBalance() }
            return if (b == b.toLong().toDouble()) b.toLong().toString()
                   else String.format("%.2f", b)
        }

        /** Online multiplayer Ludo. [stake] 0 = free room. Nothing is debited
         *  here — the board escrows the stake when the game starts. */
        @JavascriptInterface fun playFriends(stake: Int): String {
            launchLudo("online", stake); return "ok"
        }

        /** Free offline board vs three CPUs. */
        @JavascriptInterface fun playAI(): String { launchLudo("ai", 0); return "ok" }

        /** 2-4 people on this one phone, taking turns. */
        @JavascriptInterface fun playPass(): String { launchLudo("pass", 0); return "ok" }

        /** Jump straight back into a held online seat. */
        @JavascriptInterface fun resumeLudo(): String {
            runOnUiThread {
                startActivity(Intent(this@GamesActivity, LudoActivity::class.java)
                    .putExtra(LudoActivity.EXTRA_MODE, "online")
                    .putExtra(LudoActivity.EXTRA_RESUME, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            return "ok"
        }

        @JavascriptInterface fun openXr(): String {
            runOnUiThread {
                startActivity(Intent(this@GamesActivity, XrRoomActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            return "ok"
        }
    }

    private fun launchLudo(mode: String, stake: Int) {
        runOnUiThread {
            startActivity(Intent(this, LudoActivity::class.java)
                .putExtra(LudoActivity.EXTRA_MODE, mode)
                .putExtra(LudoActivity.EXTRA_STAKE, stake)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
