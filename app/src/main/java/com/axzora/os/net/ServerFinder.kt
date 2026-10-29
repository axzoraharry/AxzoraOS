package com.axzora.os.net

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.axzora.os.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Context.finderStore by preferencesDataStore("axzora_server")
private val KEY_LAST_GOOD = stringPreferencesKey("last_good_server")

/**
 * Picks a reachable backend from [Config.SERVER_CANDIDATES].
 *
 * All candidates are probed at once rather than in sequence: a dead address on
 * a mobile network can take many seconds to time out, and probing serially
 * would stack those delays before the app could talk to anything.
 *
 * The winner is remembered, so later launches go straight to the address that
 * worked last time and only fall back to a full sweep when it stops answering.
 */
class ServerFinder(private val context: Context) {

    suspend fun lastGood(): String? =
        context.finderStore.data.first()[KEY_LAST_GOOD]

    private suspend fun remember(url: String) {
        context.finderStore.edit { it[KEY_LAST_GOOD] = url }
    }

    /** True when this address answers /health in time. */
    suspend fun probe(base: String, timeoutMs: Int = 12_000): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("${base.trimEnd('/')}/health").openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.requestMethod = "GET"
                val ok = c.responseCode == 200
                c.disconnect()
                ok
            }.getOrDefault(false)
        }

    /**
     * Returns a working server, preferring the one that worked last time.
     * Falls back to the first candidate so callers always get a usable string.
     */
    suspend fun find(): String = coroutineScope {
        lastGood()?.let { cached ->
            if (probe(cached, timeoutMs = 8000)) return@coroutineScope cached
        }

        // Probe everything concurrently, then pick by list order so the most
        // permanent reachable host wins even if a slower one replies first.
        val results = Config.SERVER_CANDIDATES.map { url ->
            async { url to probe(url) }
        }.map { it.await() }

        val winner = results.firstOrNull { it.second }?.first
        if (winner != null) {
            remember(winner)
            winner
        } else {
            Config.DEFAULT_SERVER
        }
    }
}
