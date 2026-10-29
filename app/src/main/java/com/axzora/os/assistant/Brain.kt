package com.axzora.os.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The part of the assistant that can hold a real conversation.
 *
 * [Conversation] answers the things the phone owns outright — greetings,
 * memory, arithmetic — and [CommandEngine] carries out instructions. Neither
 * can answer an open question like "why does my bike stall in the rain", so
 * this asks the model through the Axzora server.
 *
 * The API key lives on the server, never in the APK: anything shipped inside
 * an app can be read by whoever unpacks it. When there's no signal, or the
 * server has no key configured, [ask] returns null and the caller falls back
 * to the on-device rules — so the assistant degrades quietly instead of
 * failing in the user's face.
 */
class Brain(
    private val baseUrl: () -> String,
    /** Optional local LLM (Ollama, OpenAI-compatible) on the home network —
     *  a private brain that answers without the cloud when it's reachable. */
    private val localBrainUrl: () -> String = { "" },
    /** The phone's OWN on-device brain (odysseus happy-brain, :9095, OpenAI-
     *  compatible). Tried after the LAN brain — it needs no network at all, so
     *  Mr Happy still answers with no Wi-Fi and no PC. */
    private val onDeviceBrainUrl: () -> String = { "" },
) {

    data class Turn(val text: String, val fromUser: Boolean)

    /** Why the last question went unanswered, so the reply can say so. */
    enum class Miss { NONE, NOT_CONFIGURED, UNREACHABLE }

    @Volatile
    var lastMiss: Miss = Miss.NONE
        private set

    @Volatile
    var enabled: Boolean = true

    /** True once a reply has come back, so the UI can say the brain is live. */
    @Volatile
    var everWorked: Boolean = false
        private set

    /**
     * @param history recent turns, oldest first. Only the last few are sent.
     * @param memory  facts the user asked to be remembered, as plain text.
     * @return the reply, or null if the model couldn't be reached.
     */
    suspend fun ask(
        history: List<Turn>,
        userName: String,
        memory: String,
    ): String? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        if (history.none { it.fromUser }) return@withContext null

        // Local brains first, in order of preference:
        //   1. the LAN brain (a PC on the home network — biggest model), then
        //   2. the phone's OWN on-device brain (always available, no network).
        // Each is OpenAI-compatible; the first that answers wins. Anything left
        // falls through to the cloud server.
        for (url in listOf(localBrainUrl().trim(), onDeviceBrainUrl().trim())) {
            if (url.isEmpty()) continue
            askLocal(url, history, userName, memory)?.let {
                everWorked = true; lastMiss = Miss.NONE
                return@withContext it
            }
        }

        runCatching {
            val messages = JSONArray()
            history.takeLast(12).forEach { t ->
                messages.put(
                    JSONObject()
                        .put("role", if (t.fromUser) "user" else "assistant")
                        .put("text", t.text),
                )
            }
            val body = JSONObject()
                .put("messages", messages)
                .put("userName", userName)
                .put("memory", memory)

            val c = URL("${baseUrl().trimEnd('/')}/ai/chat").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            // A thinking model needs longer than a plain lookup would.
            c.readTimeout = 40000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }

            val text = if (c.responseCode in 200..299) {
                c.inputStream.bufferedReader().readText()
            } else {
                c.errorStream?.bufferedReader()?.readText().orEmpty()
            }
            c.disconnect()

            val json = JSONObject(text)
            if (!json.optBoolean("ok")) {
                lastMiss = if (json.optString("error") == "ai_not_configured") {
                    Miss.NOT_CONFIGURED
                } else {
                    Miss.UNREACHABLE
                }
                return@runCatching null
            }
            json.optString("reply").trim().ifBlank { null }
        }.also { if (it.isFailure) lastMiss = Miss.UNREACHABLE }
            .getOrNull()
            ?.also { everWorked = true; lastMiss = Miss.NONE }
    }

    /**
     * Ask a local OpenAI-compatible server (Ollama's /v1/chat/completions).
     * [url] is the base, e.g. http://192.168.29.250:11434 — the model defaults
     * to whatever the server has loaded via a well-known small default.
     */
    private fun askLocal(
        url: String, history: List<Turn>, userName: String, memory: String,
    ): String? = runCatching {
        val root = url.trimEnd('/')
        val base = if (root.endsWith("/v1/chat/completions")) root
        else if (root.endsWith("/v1")) "$root/chat/completions"
        else "$root/v1/chat/completions"

        val messages = JSONArray()
        val sys = buildString {
            append("You are Axzora, the assistant on ")
            append(userName.ifBlank { "the user" })
            append("'s phone. Answer briefly and plainly, no markdown.")
            if (memory.isNotBlank()) append("\nRemembered: ").append(memory)
        }
        messages.put(JSONObject().put("role", "system").put("content", sys))
        history.takeLast(12).forEach { t ->
            messages.put(
                JSONObject()
                    .put("role", if (t.fromUser) "user" else "assistant")
                    .put("content", t.text),
            )
        }
        // The phone's own router (:9095) serves "glm-5.2-colibri"; a PC Ollama
        // serves the configured model. Pick the one the endpoint expects.
        val model = if (root.contains(":9095") || root.contains("127.0.0.1"))
            "glm-5.2-colibri" else localModel
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)

        val c = URL(base).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 2500          // on the LAN this is instant; give up fast if away
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = if (c.responseCode in 200..299)
            c.inputStream.bufferedReader().readText()
        else { c.disconnect(); return@runCatching null }
        c.disconnect()

        JSONObject(text).optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.trim()?.ifBlank { null }
    }.getOrNull()

    /** The model name the local server serves. */
    @Volatile
    var localModel: String = "qwen2.5:7b-instruct"
}
