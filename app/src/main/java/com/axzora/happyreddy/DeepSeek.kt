package com.axzora.happyreddy

import android.content.Context
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mr Happy's brain on DeepSeek — OpenAI-compatible chat with the same
 * device-control tools Gemini used (open apps, read screen, tap/type, calls,
 * messages, alarms, navigation, etc.). The loop: DeepSeek decides → if it asks
 * for a tool, [DeviceActions] runs it (real actions) → the result is fed back →
 * DeepSeek continues, up to a bounded number of tool rounds.
 */
class DeepSeek(private val context: Context) {

    // Model name is editable in Settings and falls back to Config. The API KEY
    // is deliberately NOT here — it lives server-side (see server /ai/deepseek).
    private fun prefs() = context.getSharedPreferences("axzora_ai_options", Context.MODE_PRIVATE)
    private val model: String get() = prefs().getString("ai_model", "")
        ?.takeIf { it.isNotBlank() } ?: com.axzora.os.Config.DEEPSEEK_MODEL

    interface Listener {
        fun onModelText(text: String)
        fun onTool(name: String, result: String)
        fun onState(state: String)
        fun onError(message: String)
        /**
         * Called for sensitive actions (sending a message/call, or admin mint /
         * notify) so the UI can show a yes/no dialog. [args] is the tool's JSON
         * arguments; call [resume] with true to run the action or false to cancel.
         */
        fun onConfirm(name: String, args: JSONObject, resume: (Boolean) -> Unit) {
            // Default: refuse silently. Only a host that can show a real dialog
            // (MainActivity) overrides this; with no screen we never act.
            resume(false)
        }
    }

    fun ask(userText: String, listener: Listener) {
        Thread {
            try {
                val messages = ArrayList<JSONObject>()
                messages.add(JSONObject().put("role", "system").put("content", systemPrompt()))
                messages.add(JSONObject().put("role", "user").put("content", userText))
                val tools = toOpenAiTools(DeviceActions.declarations(context))
                var rounds = 0
                var finished = false
                while (!finished && rounds < 8) {
                    listener.onState("thinking")
                    val resp = chat(messages, tools)
                    val msg = resp.optJSONArray("choices")
                        ?.getJSONObject(0)?.optJSONObject("message") ?: break
                    val content = msg.optString("content")
                    val toolCalls = msg.optJSONArray("tool_calls")
                    if (toolCalls == null || toolCalls.length() == 0) {
                        // Never leave the user with a blank reply — that's "silence".
                        val finalText =
                            if (content.isNotBlank()) content
                            else "I'm not sure what to do there — try rephrasing, or say 'read the screen' / 'run ls in Termux'."
                        com.axzora.os.assistant.Perception.record(context, "agent", finalText.take(200))
                        listener.onModelText(finalText)
                        finished = true
                    } else {
                        if (content.isNotBlank()) listener.onModelText(content)
                        listener.onState("acting")
                        messages.add(msg) // assistant message carrying the tool_calls
                        val results = mutableListOf<JSONObject>()
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val fn = tc.optJSONObject("function")
                            val name = fn?.optString("name") ?: continue
                            val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                                .getOrElse { JSONObject() }
                            val result = runTool(listener, name, args)
                            // Reflection: record this attempt and whether it looked
                            // successful, so the model starts from its own history.
                            com.axzora.os.assistant.LearnedRules.record(
                                context, name,
                                args.optString("command", "").ifBlank { args.optString("query", "").ifBlank { "" } },
                                result, isHelpful(result)
                            )
                            listener.onTool(name, result)
                            results.add(JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", tc.optString("id"))
                                .put("content", result))
                        }
                        messages.addAll(results)
                        rounds++
                    }
                }
                if (!finished) listener.onModelText("(That took a lot of steps — tell me the next step.)")
            } catch (e: Exception) {
                listener.onError(e.message ?: "DeepSeek error")
            } finally {
                listener.onState("done")
            }
        }.start()
    }

    // Runs one tool. Sensitive actions (sending a message/call, admin mint /
    // notify) go through the host's confirm dialog first; without a screen the
    // default refuses, so nothing acts unless the operator said yes.
    private fun runTool(listener: Listener, name: String, args: JSONObject): String {
        if (!DeviceActions.isSensitive(name)) return DeviceActions.execute(context, name, args)
        val latch = java.util.concurrent.CountDownLatch(1)
        var approved = false
        listener.onConfirm(name, args) { ok ->
            approved = ok
            latch.countDown()
        }
        // Wait (up to a generous 2 minutes) for the user to answer the dialog.
        try { latch.await(120, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        return if (approved) {
            DeviceActions.execute(context, name, args)
        } else {
            "Cancelled — you said no, so I didn't do it."
        }
    }

    private fun chat(messages: List<JSONObject>, tools: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().apply { messages.forEach { put(it) } })
            .put("tools", tools)
            .put("max_tokens", 1400)

        // Offline + online, automatic. The "Use on-device model" toggle only sets
        // the PREFERRED brain; either way we fall back to the other if the first
        // is down. So Mr Happy works with no internet AND when the cloud is up —
        // no manual switching.
        val prefs = context.getSharedPreferences("axzora_ai_options", Context.MODE_PRIVATE)
        val useLocal = prefs.getBoolean("use_local_brain", false)
        val localUrl = com.axzora.os.Config.LOCAL_BRAIN_URL.trim()
        val cloudUrl = serverProxyUrl()

        // Build a local-friendly body WITHOUT tools: small on-device models
        // answer as plain chat; tool-calling is what the cloud brain does.
        val localBody = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().apply { messages.forEach { put(it) } })
            .put("max_tokens", 1400)

        // Order by preference, then auto-fall back. A "success" is a response
        // carrying choices; anything else (offline, empty, error) falls through.
        val candidates = if (useLocal) {
            listOf(localUrl to localBody, cloudUrl to body)
        } else {
            listOf(cloudUrl to body, localUrl to localBody)
        }
        for ((url, payload) in candidates) {
            if (url.isEmpty()) continue
            val r = runCatching { post(url, payload, null) }.getOrNull()
            if (r != null && r.optJSONArray("choices") != null) return r
        }
        // Everything is down (no internet + no local model). Throw so the caller's
        // catch turns it into a clear message instead of a blank reply.
        throw RuntimeException("No AI brain reachable — offline and no on-device model running.")
    }

    /** Resolve the Axzora backend this phone talks to, then target /ai/deepseek. */
    private fun serverProxyUrl(): String {
        val base: String? = runCatching {
            kotlinx.coroutines.runBlocking {
                com.axzora.os.data.SettingsStore(context).settings.first().serverUrl
            }
        }.getOrNull()
        val resolved = if (!base.isNullOrBlank()) base else com.axzora.os.Config.DEFAULT_SERVER
        return "${resolved.trimEnd('/')}/ai/deepseek"
    }

    private fun post(url: String, body: JSONObject, token: String?): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return JSONObject(text)
    }

    private fun systemPrompt(): String {
        val os = "Android " + android.os.Build.VERSION.RELEASE
        val model = android.os.Build.MODEL
        val brand = android.os.Build.BRAND
        return "You are Mr Happy, the AI assistant built into Axzora OS on this phone. " +
            "You DO things on this device with the provided tools rather than only describing them. " +
            "Be helpful, concise, and honest about what actually happened.\n\n" +
            "DEVICE: $brand $model, $os.\n\n" +
            "HOW TO ACT:\n" +
            "- To list files or run anything in Termux, call run_termux (e.g. command \"ls\") and report the output. " +
            "Do NOT try to type into the Termux terminal; run the command instead.\n" +
            "- To see what's on screen, call read_screen.\n" +
            "- To tap/type/scroll/click, use tap_text / tap_at / type_into_screen / swipe_screen / find_and_tap.\n" +
            "- To open an app, call open_app. To place a call, phone_call. To message, send_whatsapp / send_sms.\n" +
            "- For the weather, use weather; to look up facts, use web_search; for routing, use navigate/traffic.\n" +
            "- Chain tools to complete a task (e.g. read_screen, then tap_text on the result, then type_into_screen).\n" +
            "- If a tool fails or returns 'no result', say so ONCE and suggest the fix (e.g. Termux isn't installed / needs allow-external-apps), " +
            "then stop — do NOT keep repeating the same command or asking 'run again?'.\n" +
            "- Be concise: one or two sentences, then done.\n" +
            "- ALWAYS confirm before sending a message, placing a call, or anything that acts externally; " +
            "otherwise just do it and report the result." +
            perceptionBlock() +
            learnedBlock()
    }

    /** Recent on-device context folded into the prompt so replies aren't in a vacuum. */
    private fun perceptionBlock(): String {
        return com.axzora.os.assistant.Perception.snapshot(context)
            .let { if (it.isNotBlank()) "\n\n$it" else "" }
    }

    /** Lessons from past attempts folded in, so the model starts from experience. */
    private fun learnedBlock(): String {
        return com.axzora.os.assistant.LearnedRules.lessons(context)
            .let { if (it.isNotBlank()) "\n\n$it" else "" }
    }

    /** Heuristic: is a tool result "successful" enough to learn from? */
    private fun isHelpful(result: String): Boolean {
        val r = result.lowercase()
        return !(r.startsWith("failed:") || r.startsWith("blocked:") ||
            r.startsWith("cancelled") || r.startsWith("no result") ||
            r.startsWith("unreachable") || r.startsWith("error") ||
            r.startsWith("i couldn't") || r.startsWith("that needs") ||
            r.startsWith("unable to") || r.startsWith("server unreachable"))
    }

    /** Converts the Gemini-style declarations to OpenAI tool schema (lowercased types). */
    private fun toOpenAiTools(decls: JSONArray): JSONArray {        val out = JSONArray()
        for (i in 0 until decls.length()) {
            val d = decls.optJSONObject(i) ?: continue
            val fn = JSONObject().put("name", d.optString("name"))
                .put("description", d.optString("description"))
            val params = d.optJSONObject("parameters")
            params?.let { fn.put("parameters", lowerTypes(it)) }
            out.put(JSONObject().put("type", "function").put("function", fn))
        }
        return out
    }

    private fun lowerTypes(schema: JSONObject): JSONObject {
        val out = JSONObject()
        if (schema.has("type")) out.put("type", schema.optString("type").lowercase())
        schema.keys().forEach { k ->
            if (k == "type") return@forEach
            when (val v = schema.get(k)) {
                is JSONObject -> out.put(k, lowerTypes(v))
                is JSONArray -> {
                    val arr = JSONArray()
                    for (j in 0 until v.length()) {
                        val item = v.opt(j)
                        arr.put(if (item is JSONObject) lowerTypes(item) else item)
                    }
                    out.put(k, arr)
                }
                else -> out.put(k, v)
            }
        }
        return out
    }
}
