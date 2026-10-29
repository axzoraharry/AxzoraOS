package com.axzora.os.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Commute / traffic helper, shared by Axzora's own assistant (CommandEngine)
 * and Mr Happy (DeviceActions).
 *
 * It remembers home/work addresses, resolves "home"/"work" to those saved
 * addresses, and answers "how's the traffic on my commute" in one of two ways:
 *  - If a Google Directions API key is configured, it fetches the real
 *    drive time (including live traffic) and speaks it.
 *  - Otherwise it opens the route in Google Maps with the live traffic layer.
 *
 * Everything is on the user's own device; no key, no data leaves the phone
 * beyond the (optional) Directions API call.
 */
object Traffic {

    private const val PREFS = "axzora_traffic"
    private const val KEY_HOME = "home"
    private const val KEY_WORK = "work"
    private const val KEY_DIRECTIONS = "directions_key"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remember home/work so "the commute" needs no spoken addresses. */
    fun setAddress(ctx: Context, which: String, value: String) {
        val key = when (which.trim().lowercase()) {
            "home", "house", "my home", "the house" -> KEY_HOME
            "work", "office", "my work", "the office" -> KEY_WORK
            else -> return
        }
        prefs(ctx).edit().putString(key, value.trim()).apply()
    }

    fun home(ctx: Context): String? = prefs(ctx).getString(KEY_HOME, null)?.takeIf { it.isNotBlank() }
    fun work(ctx: Context): String? = prefs(ctx).getString(KEY_WORK, null)?.takeIf { it.isNotBlank() }

    /** Optional Google Directions API key. When set, a real drive-time answer is spoken. */
    fun setDirectionsKey(ctx: Context, key: String) =
        prefs(ctx).edit().putString(KEY_DIRECTIONS, key.trim()).apply()
    fun directionsKey(ctx: Context): String? =
        prefs(ctx).getString(KEY_DIRECTIONS, null)?.takeIf { it.isNotBlank() }

    /** Rewrites "home"/"work" to the stored address; anything else passes through. */
    fun resolve(ctx: Context, query: String?): String? {
        if (query == null) return null
        val s = query.trim()
        if (s.isEmpty()) return null
        return when (s.lowercase()) {
            "home", "house", "my home" -> home(ctx)
            "work", "office", "my work" -> work(ctx)
            else -> s
        }
    }

    /**
     * Pulls the two endpoints out of a traffic phrase.
     *   "traffic from Kothapet to Secunderabad" -> ("kothapet", "secunderabad")
     *   "fastest way to work"                   -> (null, "work")
     *   "traffic on my commute"                 -> (null, null)
     */
    fun parsePlaces(raw: String): Pair<String?, String?> {
        val t = raw.lowercase().replace("'", "")
        Regex("from (.+?) to (.+)$").find(t)?.let { m -> return m.groupValues[1].trim() to m.groupValues[2].trim() }
        Regex("(?:to|towards?|into)\\s+(.+)$").find(t)?.let { m -> return null to m.groupValues[1].trim() }
        return null to null
    }

    /** True when the utterance is asking about traffic/commute. */
    fun isTrafficLike(raw: String): Boolean {
        val t = raw.lowercase()
        return t.contains("traffic") || t.contains("commute") ||
            t.contains("fastest way") || t.contains("delay") ||
            t.contains("route") || t.contains("how long")
    }

    /**
     * No-key path: resolve home/work (defaulting a missing endpoint to the
     * saved address) and open the route in Google Maps with the live traffic
     * layer. Always safe and synchronous — no network involved.
     */
    fun syncReply(ctx: Context, fromRaw: String?, toRaw: String?): String {
        val from = resolve(ctx, fromRaw) ?: home(ctx)
        val to = resolve(ctx, toRaw) ?: work(ctx)
        if (from.isNullOrBlank() || to.isNullOrBlank()) return guidance(ctx)
        openRoute(ctx, from, to)
        return "Opening your route ($from \u2192 $to) with the live traffic layer."
    }

    /**
     * Key path: fetch the real drive time (with live traffic) and speak it.
     * Falls back to [syncReply] if anything can't be resolved or the API errors.
     */
    suspend fun liveReply(ctx: Context, fromRaw: String?, toRaw: String?, key: String): String {
        val from = resolve(ctx, fromRaw) ?: home(ctx)
        val to = resolve(ctx, toRaw) ?: work(ctx)
        if (from.isNullOrBlank() || to.isNullOrBlank()) return guidance(ctx)
        return withContext(Dispatchers.IO) {
            val answer = routeAnswer(from, to, key)
            if (answer != null) answer
            else { openRoute(ctx, from, to); "I couldn't check live traffic, so I opened your route ($from \u2192 $to) instead." }
        }
    }

    private fun routeAnswer(from: String, to: String, key: String): String? = try {
        val u = URL(
            "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=" + Uri.encode(from) +
                "&destination=" + Uri.encode(to) +
                "&mode=driving&alternatives=true&key=" + Uri.encode(key)
        )
        val conn = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val j = JSONObject(body)
        if (j.optString("status").uppercase() != "OK") null
        else {
            val leg = j.getJSONArray("routes").optJSONObject(0)
                ?.getJSONArray("legs")?.optJSONObject(0) ?: return null
            val dur = leg.optJSONObject("duration")?.optString("text") ?: ""
            val traf = leg.optJSONObject("duration_in_traffic")?.optString("text") ?: ""
            when {
                traf.isNotBlank() && dur.isNotBlank() && traf != dur ->
                    "It's about $traf by car from $from to $to (usually $dur)."
                traf.isNotBlank() -> "About $traf by car from $from to $to."
                dur.isNotBlank() -> "About $dur by car from $from to $to."
                else -> null
            }
        }
    } catch (e: Exception) { null }

    private fun guidance(ctx: Context): String {
        val h = home(ctx)
        val w = work(ctx)
        return if (h.isNullOrBlank() && w.isNullOrBlank()) {
            "I can check your commute once I know where you live and work. " +
                "Say \"my home is <address>\" and \"my work is <address>\", or just " +
                "say \"traffic from someplace to someplace\". For example: " +
                "\"traffic from Kothapet to Secunderabad\"."
        } else {
            "Tell me where you're going — try \"traffic from Kothapet to " +
                "Secunderabad\", or \"fastest way to work\"."
        }
    }

    /** Opens Google Maps navigation to the route, traffic layer included. */
    private fun openRoute(ctx: Context, from: String, to: String) {
        runCatching {
            val uri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1" +
                    "&origin=" + Uri.encode(from) +
                    "&destination=" + Uri.encode(to) +
                    "&travelmode=driving"
            )
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
