package com.axzora.os.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Starts and monitors the local llama-server (llama.cpp) that Mr Happy uses as
 * its offline brain. The assistant reads [com.axzora.os.Config.LOCAL_BRAIN_URL]
 * (http://127.0.0.1:8080/v1/chat/completions); this is the companion that brings
 * that server up and reports whether it is answering.
 *
 * The llama-server binary runs inside the device's Termux-like shell. This uses
 * the same multi-shell auto-detect as [com.axzora.os.openroom.OpenRoom], so it
 * works on the bundled shell, stock Termux, or Termux FOSS — no Config edits.
 */
object LocalBrainServer {

    const val HOST = "127.0.0.1"
    const val PORT = 8080

    /** Default model path, relative to the shell's home (used when none is set). */
    private const val DEFAULT_MODEL =
        "storage/downloads/qwen2.5-1.5b-instruct-q8_0.gguf"

    /** True when the llama-server answers on :8080. */
    fun isUp(timeoutMs: Int = 800): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), timeoutMs) }
        true
    }.getOrDefault(false)

    /** A quick probe: does it return OpenAI-shaped JSON? */
    fun isHealthy(timeoutMs: Int = 4000): Boolean = runCatching {
        val c = java.net.URL("http://$HOST:$PORT/v1/chat/completions").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":4}".toByteArray()) }
        c.responseCode == 200
    }.getOrDefault(false)

    private data class Shell(val pkg: String, val bash: String, val home: String)

    private val candidates = listOf(
        Shell("com.dsharnessmobile.shell", "/data/data/com.dsharnessmobile.shell/files/usr/bin/bash", "/data/data/com.dsharnessmobile.shell/files/home"),
        Shell("com.termux", "/data/data/com.termux/files/usr/bin/bash", "/data/data/com.termux/files/home"),
        Shell("com.termux.foss", "/data/data/com.termux.foss/files/usr/bin/bash", "/data/data/com.termux.foss/files/home"),
    )

    private fun activeShell(context: Context): Shell? {
        val pm = context.packageManager
        return candidates.firstOrNull { runCatching { pm.getPackageInfo(it.pkg, 0); true }.getOrDefault(false) }
    }

    /**
     * Start llama-server on :8080 with [modelPath]. [modelPath] may be absolute or
     * relative to the shell home (e.g. "storage/downloads/foo.gguf").
     * Fire-and-forget; returns true if the start intent fired (not if it's up).
     */
    fun start(context: Context, modelPath: String): Boolean = runCatching {
        val shell = activeShell(context) ?: return false
        val model = if (modelPath.startsWith("/")) modelPath else "${shell.home}/$modelPath"
        val cmd = buildString {
            append("if (exec 3<>/dev/tcp/$HOST/$PORT) 2>/dev/null; then exit 0; fi; ")
            append("cd ~ && nohup llama-server -m \"$model\" --host $HOST --port $PORT -c 4096 ")
            append(">llama-server.log 2>&1 &")
        }
        val i = Intent().apply {
            setClassName(shell.pkg, "${shell.pkg}.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", shell.bash)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", shell.home)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        true
    }.getOrDefault(false)
}
