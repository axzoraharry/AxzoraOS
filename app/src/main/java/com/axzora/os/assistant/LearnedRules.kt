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
 * Reflection store — the "what worked" memory that lets Mr Happy genuinely
 * improve. After each tool action it records `(action, context, outcome,
 * helpful)`; when a similar action comes up again the learned lessons are folded
 * into the prompt so the model starts from experience instead of a blank slate.
 *
 * This is the honest version of "learning" a phone can do: no weights are
 * trained, but the assistant keeps a durable, bounded log of its own attempts and
 * their results, and it actively uses them. Everything is local; nothing is
 * uploaded.
 *
 * Storage is bounded ([MAX_RULES]) and every rule is attributable (each carries a
 * timestamp and the action that produced it), so the model can be steered by its
 * own history without the log becoming an untrusted blob.
 */
object LearnedRules {

    data class Rule(
        val action: String,      // e.g. "send_sms" or "admin_mint"
        val context: String,     // a short summary of the request
        val outcome: String,     // what happened (result text, truncated)
        val helpful: Boolean,    // true = it worked; false = it failed and was auto-recorded
        val ts: Long,
    )

    private val KEY = stringPreferencesKey("rules")
    private const val MAX_RULES = 30

    private val Context.learnedStore by preferencesDataStore("axzora_learned")

    // ── write path ──────────────────────────────────────────────────────────

    /**
     * Record an attempt and its result. Call after every tool action so the model
     * can reflect. [helpful] is typically derived by the caller: a result that
     * looks like an error/blocked/cancelled string is unhelpful.
     */
    fun record(ctx: Context, action: String, context: String, outcome: String, helpful: Boolean) {
        if (action.isBlank() || outcome.isBlank()) return
        val rule = Rule(action, context.take(80), outcome.take(200), helpful, System.currentTimeMillis())
        runCatching {
            runBlocking {
                ctx.learnedStore.edit { p ->
                    val arr = readArr(p[KEY])
                    // dedupe: refresh ts if the same action+context already exists
                    var replaced = false
                    val out = JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i)
                        if (o == null) continue
                        if (o.optString("a") == rule.action &&
                            o.optString("c") == rule.context && !replaced) {
                            out.put(rule.toJson()); replaced = true
                        } else out.put(o)
                    }
                    if (!replaced) out.put(rule.toJson())
                    // keep only the most recent MAX_RULES (newest last)
                    val trimmed = JSONArray()
                    for (i in maxOf(0, out.length() - MAX_RULES) until out.length()) {
                        trimmed.put(out.optJSONObject(i))
                    }
                    p[KEY] = trimmed.toString()
                }
            }
        }
    }

    /** Drop everything (a "stop learning from that" / privacy request). */
    fun clear(ctx: Context) {
        runCatching { runBlocking { ctx.learnedStore.edit { p -> p[KEY] = "[]" } } }
    }

    // ── read path ───────────────────────────────────────────────────────────

    /** All learned rules, newest last. */
    fun rules(ctx: Context): List<Rule> = runCatching {
        runBlocking {
            val arr = readArr(ctx.learnedStore.data.first()[KEY])
            (0 until arr.length()).mapNotNull { r ->
                val o = arr.optJSONObject(r) ?: return@mapNotNull null
                Rule(
                    o.optString("a"), o.optString("c"), o.optString("o"),
                    o.optBoolean("h", true), o.optLong("t"),
                )
            }
        }
    }.getOrDefault(emptyList())

    /**
     * A prompt-friendly block of the lessons relevant to [action]. Returns "" when
     * there's nothing the model should reuse, so a specialist / fresh conversation
     * isn't handed noise. Only *helpful* (worked) rules are surfaced — failures are
     * kept for auditing but not fed back as instructions.
     */
    fun lessons(ctx: Context, action: String? = null): String {
        val all = rules(ctx)
        val list = if (action == null) all else all.filter { it.action.equals(action, ignoreCase = true) }
        val helpful = list.filter { it.helpful }.takeLast(8).ifEmpty { list.takeLast(4) }
        if (helpful.isEmpty()) return ""
        val sb = StringBuilder("WHAT I'VE LEARNED (from my own past attempts):\n")
        for (r in helpful) {
            sb.append("  • ").append(r.action)
                .append(if (r.context.isNotBlank()) " ($r.context)" else "")
                .append(" \u2192 ").append(r.outcome)
                .append("\n")
        }
        return sb.toString().trimEnd()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun Rule.toJson() = JSONObject()
        .put("a", action).put("c", context).put("o", outcome)
        .put("h", helpful).put("t", ts)

    private fun readArr(raw: String?): JSONArray =
        runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
}
