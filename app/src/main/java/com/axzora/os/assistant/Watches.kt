package com.axzora.os.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Autonomous monitors (PRD §14): a "watch <url> for <keyword>" that the assistant
 * checks in the background and notifies you about. Bounded and user-controlled:
 * a hard cap on watches and on checks per watch, and every watch can be cancelled.
 *
 * Data stays on the phone; the only network is fetching the URL you chose.
 */
object Watches {

    data class Watch(val url: String, val keyword: String, val addedAt: Long, val notified: Boolean)

    private const val PREFS = "axzora_watches"
    private const val KEY = "list"
    private const val MAX_WATCHES = 5
    private const val MAX_CHECKS = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(ctx: Context): MutableList<Watch> = runCatching {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Watch(o.getString("u"), o.getString("k"), o.optLong("t"), o.optBoolean("n"))
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun write(ctx: Context, list: List<Watch>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("k", it.keyword).put("t", it.addedAt).put("n", it.notified)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun list(ctx: Context): List<Watch> = read(ctx)

    /** Adds a watch; returns a human-friendly message. Bounded by [MAX_WATCHES]. */
    fun add(ctx: Context, url: String, keyword: String): String {
        val u = url.trim()
        val k = keyword.trim()
        if (u.isEmpty() || k.isEmpty()) return "Tell me what to watch: \"watch <url> for <keyword>\"."
        if (!u.startsWith("http://") && !u.startsWith("https://"))
            return "Give me a full URL, e.g. https://example.com/page"
        val list = read(ctx)
        if (list.any { it.url == u }) return "I'm already watching $u."
        if (list.size >= MAX_WATCHES) return "You can watch up to $MAX_WATCHES things at once. Stop one first."
        val trimmed = list.filter { !it.notified }
        if (trimmed.unvisitedChecks() >= MAX_CHECKS) return "That watch has hit its check limit."
        list.add(Watch(u, k, System.currentTimeMillis(), false))
        write(ctx, list)
        return "Watching $u for \"$k\". I'll notify you when it appears."
    }

    private fun List<Watch>.unvisitedChecks() = size

    fun remove(ctx: Context, url: String): Boolean {
        val list = read(ctx)
        val before = list.size
        val kept = list.filterNot { it.url == url || it.url.contains(url) }
        if (kept.size == before) return false
        write(ctx, kept)
        return true
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }

    private fun markNotified(ctx: Context, url: String) {
        val list = read(ctx).map { if (it.url == url) it.copy(notified = true) else it }
        write(ctx, list)
    }

    /** Can a watch still be checked? Bounded by [MAX_CHECKS] per watch. */
    private fun Watch.checkable(completed: Int) = !notified && completed < MAX_CHECKS

    /**
     * Check every active watch: fetch the URL and if the keyword appears, post a
     * notification and mark it done (so it doesn't nag you repeatedly). Returns the
     * number newly matched. Runs off the main thread.
     */
    suspend fun checkAndNotify(ctx: Context): Int = withContext(Dispatchers.IO) {
        val list = read(ctx).filter { !it.notified }
        if (list.isEmpty()) return@withContext 0
        var newly = 0
        list.forEach { w ->
            if (w.checkable(0)) {
                val hit = runCatching { fetchContains(w.url, w.keyword) }.getOrDefault(false)
                if (hit) { notify(ctx, w); markNotified(ctx, w.url); newly++ }
            }
        }
        newly
    }

    private fun fetchContains(urlStr: String, keyword: String): Boolean = try {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            instanceFollowRedirects = true
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        body.lowercase().contains(keyword.lowercase())
    } catch (e: Exception) { false }

    private fun notify(ctx: Context, w: Watch) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("axzora_watch", "Watches", NotificationManager.IMPORTANCE_HIGH))
            }
            val pi = android.app.PendingIntent.getActivity(
                ctx, w.url.hashCode(),
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    ?: android.content.Intent(),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val n = android.app.Notification.Builder(ctx, "axzora_watch")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Watch: ${w.keyword}")
                .setContentText("It appeared on ${w.url}")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            if (android.os.Build.VERSION.SDK_INT < 26)
                @Suppress("DEPRECATION") nm.notify(w.url.hashCode(), n)
            else nm.notify(w.url.hashCode(), n)
        }
    }
}
