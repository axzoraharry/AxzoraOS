package com.axzora.os.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A language model running on the phone itself.
 *
 * No server, no API key, no network once the model is on disk — the weights
 * live in the app's own storage and inference happens on the CPU/GPU in your
 * hand. Nothing you type ever leaves the device.
 *
 * The cost is size: a usable model is several hundred megabytes, far past
 * what can sit inside an APK, so it is fetched once on request and kept.
 * Until then [ready] is false and the assistant falls back to the offline
 * engine, which needs no model at all.
 */
class LocalLlm(private val context: Context) {

    /** Where the weights live once downloaded. */
    private val modelFile: File
        get() = File(context.filesDir, "assistant-model.task")

    @Volatile
    private var engine: LlmInference? = null

    @Volatile
    var lastError: String = ""
        private set

    val installed: Boolean get() = modelFile.exists() && modelFile.length() > 50_000_000

    val ready: Boolean get() = engine != null

    /** Size on disk, for the settings screen. */
    val sizeMb: Long get() = if (modelFile.exists()) modelFile.length() / 1_000_000 else 0

    /**
     * Loads the model into memory. Slow — tens of seconds on a mid-range
     * phone — so it happens once, off the main thread, and the result is
     * kept for the life of the process.
     */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext true
        if (!installed) {
            lastError = "No model installed"
            return@withContext false
        }
        runCatching {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                // Enough for a few paragraphs of reply; larger costs memory
                // on phones that have little to spare.
                .setMaxTokens(1024)
                .build()
            engine = LlmInference.createFromOptions(context, options)
            true
        }.getOrElse {
            lastError = it.message ?: "Couldn't load the model"
            engine = null
            false
        }
    }

    /** Frees the weights — several hundred MB of RAM. */
    fun unload() {
        runCatching { engine?.close() }
        engine = null
    }

    /**
     * @param history recent turns, oldest first.
     * @return the reply, or null if the model isn't loaded or failed.
     */
    suspend fun ask(
        prompt: String,
        history: List<Brain.Turn>,
        systemNote: String,
    ): String? = withContext(Dispatchers.IO) {
        val e = engine ?: return@withContext null
        runCatching {
            // A plain instruct-style transcript. Small models follow this
            // shape far more reliably than a chat template they may not have
            // been tuned on.
            val sb = StringBuilder()
            sb.append(systemNote).append("\n\n")
            history.takeLast(6).forEach { t ->
                sb.append(if (t.fromUser) "User: " else "Axzora: ")
                sb.append(t.text).append("\n")
            }
            sb.append("User: ").append(prompt).append("\nAxzora:")

            e.generateResponse(sb.toString())
                ?.substringBefore("User:")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrElse {
            lastError = it.message ?: "Model failed"
            null
        }
    }

    fun delete(): Boolean {
        unload()
        return runCatching { modelFile.delete() }.getOrDefault(false)
    }

    /**
     * Downloads the weights, reporting progress 0..1.
     *
     * Writes to a temporary file and renames on success, so an interrupted
     * download can never be mistaken for an installed model.
     */
    suspend fun download(
        url: String,
        token: String = "",
        onProgress: (Float, Long) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "assistant-model.part")
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            // A HuggingFace access token unlocks gated model repos (Gemma and
            // friends). Sent as a bearer header exactly as `huggingface-cli`
            // would; without it those repos answer 401 and nothing downloads.
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            c.connect()
            if (c.responseCode == 401 || c.responseCode == 403) {
                return@withContext "That model needs a HuggingFace token (it's gated). " +
                    "Add one in Settings, or paste a public model URL."
            }
            if (c.responseCode !in 200..299) {
                return@withContext "Download failed (${c.responseCode})"
            }
            val total = c.contentLengthLong
            tmp.outputStream().use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress(done.toFloat() / total, done)
                        else onProgress(0f, done)
                    }
                }
            }
            c.disconnect()
            if (tmp.length() < 50_000_000) {
                tmp.delete()
                return@withContext "That file is too small to be a model"
            }
            if (modelFile.exists()) modelFile.delete()
            tmp.renameTo(modelFile)
            null
        }.getOrElse {
            runCatching { tmp.delete() }
            it.message ?: "Download failed"
        }
    }

    companion object {
        /**
         * Gemma 3 1B, instruction-tuned and quantised to 4-bit — about 550MB
         * and small enough to run on an ordinary phone. Published by Google
         * in the LiteRT format MediaPipe expects.
         */
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"

        const val SYSTEM_NOTE =
            "You are Axzora, the assistant built into this phone. Answer in a warm, " +
                "plain, direct voice. Keep replies short — usually one to three sentences — " +
                "because they are read on a small screen. Use no markdown or bullet lists. " +
                "If you do not know something, say so plainly rather than guessing."
    }
}
