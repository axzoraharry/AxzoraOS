package com.axzora.os.ceo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real LLM reasoning for the CEO system. This is what turns the sub-agents from
 * deterministic templates into *thinking* employees: each agent's [Agent.work]
 * can call [think] to produce a genuinely reasoned answer, then the CEO folds
 * that into its report.
 *
 * Routing (cheap-first → smartest):
 *   1. on-device llama (if the user enabled "Use on-device model")
 *   2. cloud DeepSeek (smart, reliable)
 *
 * If no model answers (offline, no key, model down), [think] returns null and the
 * caller falls back to its deterministic plan — so the CEO *always* responds, it
 * just reasons when it can.
 */
object Reasoner {

    /** A short, bounded reasoning call. Returns the model's text, or null. */
    suspend fun think(
        ctx: Context,
        system: String,
        prompt: String,
        maxTokens: Int = 500,
    ): String? = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("axzora_ai_options", Context.MODE_PRIVATE)
        val model = prefs.getString("ai_model", "")?.takeIf { it.isNotBlank() }
            ?: com.axzora.os.Config.DEEPSEEK_MODEL
        val useLocal = prefs.getBoolean("use_local_brain", false)
        val local = com.axzora.os.Config.LOCAL_BRAIN_URL.trim()
        val cloud = serverProxyUrl(ctx)

        // Offline + online, automatic: try the preferred brain, then fall back to
        // the other if it's down. Works with no internet AND when the cloud is up.
        val candidates = if (useLocal) listOf(local, cloud) else listOf(cloud, local)
        for (url in candidates) {
            if (url.isEmpty()) continue
            val r = runCatching { post(url, system, prompt, model, maxTokens) }.getOrNull()
            if (!r.isNullOrBlank()) return@withContext r
        }
        null
    }

    /** The Axzora backend's /ai/deepseek proxy — the API key never ships in the APK. */
    private fun serverProxyUrl(ctx: Context): String {
        val base: String? = runCatching {
            kotlinx.coroutines.runBlocking {
                com.axzora.os.data.SettingsStore(ctx).settings.first().serverUrl
            }
        }.getOrNull()
        val resolved = if (!base.isNullOrBlank()) base else com.axzora.os.Config.DEFAULT_SERVER
        return "${resolved.trimEnd('/')}/ai/deepseek"
    }

    private fun post(url: String, system: String, prompt: String, model: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))
            .put("max_tokens", maxTokens)
            .put("temperature", 0.4)
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return JSONObject(text).optJSONArray("choices")?.getJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
    }
}
