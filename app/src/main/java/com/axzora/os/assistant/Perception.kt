package com.axzora.os.assistant

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Realtime perception buffer — the "what just happened on this device" memory the
 * model reads every turn so Mr Happy is aware of recent context, not just the
 * current frame.
 *
 * Unlike a plain chat history, this records *perception*: what the screen showed,
 * what the user said, what Mr Happy did, and the current device state. A ring
 * buffer keeps the most recent [windowMs] / [maxEvents], and [Snapshot] turns the
 * whole thing into a compact text block the prompt can ingest.
 *
 * It is bounded (a hard cap on events and on age), so it cannot grow into
 * memory pressure or let stale context poison a reply. Data is synced to local
 * DataStore so a re-render / process death briefly doesn't blank the awareness.
 *
 * Events are appended by the various clients (a11y screen capture, the text/voice
 * entry path, tool execution) via [record]. The UI never calls this directly —
 * only the assistant pipeline does.
 */
object Perception {

    /** A single perceived event in the buffer. */
    data class Event(
        val ts: Long,          // epoch ms
        val kind: String,      // "screen" | "user" | "agent" | "action" | "device"
        val text: String,      // the observed content
    )

    private val KEY = stringPreferencesKey("events")
    private val KEY_SNAPSHOT = stringPreferencesKey("snapshot")
    private const val MAX_EVENTS = 40
    private const val WINDOW_MS = 120 * 1000L   // forget anything older than 2 min

    private val Context.perceptionStore by preferencesDataStore("axzora_perception")

    // ── write path (called from the pipeline) ───────────────────────────────

    /** Append an observed event and evict anything that fell out of the window. */
    fun record(ctx: Context, kind: String, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val e = Event(now, kind, text)
        runCatching {
            runBlocking {
                ctx.perceptionStore.edit { p ->
                    val arr = readArr(p[KEY])
                    arr.put(JSONObject().put("t", e.ts).put("k", e.kind).put("x", e.text))
                    // keep only the newest window
                    val filtered = JSONArray()
                    for (i in arr.length() - 1 downTo 0) {
                        val o = arr.optJSONObject(i)
                        if (o == null || o.optLong("t") < now - WINDOW_MS) continue
                        filtered.put(o)
                        if (filtered.length() >= MAX_EVENTS) break
                    }
                    // reverse back to chronological
                    val out = JSONArray()
                    for (i in filtered.length() - 1 downTo 0) out.put(filtered.optJSONObject(i))
                    p[KEY] = out.toString()
                }
            }
        }
    }

    /** Force-clear the buffer (e.g. a privacy "forget what I did" request). */
    fun clear(ctx: Context) {
        runCatching { runBlocking { ctx.perceptionStore.edit { p -> p[KEY] = "[]" } } }
    }

    // ── read path (called by the prompt builder) ────────────────────────────

    /** The recent events, oldest → newest, within the window. */
    fun recent(ctx: Context): List<Event> {
        val now = System.currentTimeMillis()
        return runCatching {
            runBlocking {
                val arr = readArr(ctx.perceptionStore.data.first()[KEY])
                (0 until arr.length()).mapNotNull {
                    val o = arr.optJSONObject(it) ?: return@mapNotNull null
                    val ts = o.optLong("t")
                    if (ts < now - WINDOW_MS) return@mapNotNull null
                    Event(ts, o.optString("k", "?"), o.optString("x", ""))
                }.filter { it.text.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * A compact, self-describing block for the prompt. Empty when there's nothing
     * recent — so a fresh start doesn't get a noise header.
     */
    fun snapshot(ctx: Context): String {
        val ev = recent(ctx)
        if (ev.isEmpty()) return ""
        val sb = StringBuilder("RECENT CONTEXT (last ${ev.size} event(s)):\n")
        for (e in ev.takeLast(MAX_EVENTS)) {
            val hh = when (e.kind) {
                "user" -> "User said"
                "agent" -> "I replied"
                "screen" -> "On screen"
                "action" -> "I did"
                "device" -> "Device"
                else -> e.kind
            }
            sb.append("  • ").append(hh).append(": ").append(e.text.ifBlank { "—" })
                .append("\n")
        }
        // persist a rendered copy so a cold render can still show "recent", though
        // the in-memory list is the authoritative source.
        runCatching { runBlocking { ctx.perceptionStore.edit { p -> p[KEY_SNAPSHOT] = sb.toString() } } }
        return sb.toString().trimEnd()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readArr(raw: String?): JSONArray =
        runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
}
