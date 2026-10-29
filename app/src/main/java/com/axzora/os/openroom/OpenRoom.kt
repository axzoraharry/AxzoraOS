package com.axzora.os.openroom

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.net.InetSocketAddress
import java.net.Socket

/**
 * OpenRoom — the AI desktop (React + Vite) served on port 3000 and shown in a
 * WebView. This is the glue: detect which Termux-like shell is installed, where
 * the project lives, whether the server is up, and how to start it — robustly.
 *
 * The path differs by device: some installs use the bundled shell
 * (com.dsharnessmobile.shell) at its own home, others use stock Termux
 * (com.termux) at /data/data/com.termux/files/home. [OpenRoom] probes a list of
 * candidates so the OpenRoom tile works on both without editing Config.
 */
object OpenRoom {

    /** Same phone, so loopback first; the LAN address is the fallback. */
    const val LOCAL_URL = "http://127.0.0.1:3000/"
    const val PORT = 3000

    /** Spoken names → OpenRoom's route paths. */
    val APPS = linkedMapOf(
        "home" to "home", "twitter" to "twitter", "music" to "musicPlayer",
        "music player" to "musicPlayer", "diary" to "diary", "album" to "album",
        "photos" to "album", "gallery" to "album", "freecell" to "freecell",
        "email" to "email", "mail" to "email", "gomoku" to "gomoku",
        "chess" to "chess", "evidence vault" to "evidencevault",
        "evidence" to "evidencevault", "vault" to "evidencevault",
        "news" to "cyberNews", "cyber news" to "cyberNews", "cybernews" to "cyberNews",
    )

    /** Resolve a spoken app name to a route path, or "" for the desktop. */
    fun pathFor(name: String?): String {
        val q = name?.trim()?.lowercase() ?: return ""
        if (q.isEmpty()) return ""
        APPS[q]?.let { return it }
        for ((k, v) in APPS) if (q.contains(k) || k.contains(q)) return v
        return ""
    }

    fun url(path: String): String =
        url("127.0.0.1", path)

    /**
     * OpenRoom on a given host. [host] is the machine serving OpenRoom — on the
     * same phone that's "127.0.0.1", but when the OpenRoom server runs on the
     * companion computer (the one serving updates) the app must use that machine's
     * LAN address so the phone can actually reach it.
     */
    fun url(host: String, path: String): String {
        val base = "http://${host.ifBlank { "127.0.0.1" }}:$PORT"
        return if (path.isBlank()) base + "/" else base + "/" + path
    }

    // Candidate shells (package, bash path, home, project dir). Tried in order;
    // the first whose package is installed AND whose project dir exists wins.
    // This makes OpenRoom work on the bundled shell, stock Termux, or Termux FOSS.
    private data class Shell(
        val pkg: String,
        val bash: String,
        val home: String,
        val dir: String,
    )

    private val candidates = listOf(
        Shell(
            "com.dsharnessmobile.shell",
            "/data/data/com.dsharnessmobile.shell/files/usr/bin/bash",
            "/data/data/com.dsharnessmobile.shell/files/home",
            "/data/data/com.dsharnessmobile.shell/files/home/OpenRoom/apps/webuiapps",
        ),
        Shell(
            "com.termux",
            "/data/data/com.termux/files/usr/bin/bash",
            "/data/data/com.termux/files/home",
            "/data/data/com.termux/files/home/OpenRoom/apps/webuiapps",
        ),
        Shell(
            "com.termux.foss",
            "/data/data/com.termux.foss/files/usr/bin/bash",
            "/data/data/com.termux.foss/files/home",
            "/data/data/com.termux.foss/files/home/OpenRoom/apps/webuiapps",
        ),
        // The workspace copy (repo checkout), for dev/agent use.
        Shell(
            "com.dsharnessmobile.shell",
            "/data/data/com.dsharnessmobile.shell/files/usr/bin/bash",
            "/data/data/com.dsharnessmobile.shell/files/home",
            "/storage/emulated/0/XxX/OpenRoom-main/apps/webuiapps",
        ),
    )

    /** The shell this device can actually use, or null if none is usable. */
    private fun activeShell(context: Context): Shell? {
        val pm: PackageManager = context.packageManager
        for (s in candidates) {
            val pkgInstalled = runCatching { pm.getPackageInfo(s.pkg, 0); true }.getOrDefault(false)
            if (!pkgInstalled) continue
            // Prefer a dir that actually exists on disk.
            val dirExists = java.io.File(s.dir).isDirectory
            if (dirExists) return s
        }
        // None matched a real dir — still return the first installed package so
        // the start attempt at least fires (the server may come up from a dir we
        // can't stat for other reasons).
        return candidates.firstOrNull { runCatching { pm.getPackageInfo(it.pkg, 0); true }.getOrDefault(false) }
    }

    fun isUp(timeoutMs: Int = 700): Boolean = isUp("127.0.0.1", timeoutMs)

    /** Poll a specific host (usually the device host, same as the server URL). */
    fun isUp(host: String, timeoutMs: Int = 700): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, PORT), timeoutMs) }
        true
    }.getOrDefault(false)

    /**
     * Ask the device's shell to start the dev server (fire-and-forget). Tries the
     * shell's RUN_COMMAND service; needs allow-external-apps=true in that shell's
     * termux.properties.
     */
    fun start(context: Context): Boolean = runCatching {
        val shell = activeShell(context) ?: return false
        val vite = findVite(shell.dir)
        val cmd = buildString {
            append("if (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then exit 0; fi; ")
            append("cd ${shell.dir} && mkdir -p logs && nohup node $vite --host 127.0.0.1 --port $PORT ")
            append(">logs/openroom-dev.log 2>&1 &")
        }
        val i = Intent().apply {
            setClassName(shell.pkg, "$shell.pkg.app.RunCommandService")
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

    /** The shell's home dir (where its own scripts live), or null. */
    fun shellHome(context: Context): String? = activeShell(context)?.home

    /**
     * Run an arbitrary shell command on the device's shell (fire-and-forget).
     * Used by the keep-alive service to bring the backend/OpenRoom servers back.
     */
    fun runShell(context: Context, cmd: String, workdir: String): Boolean = runCatching {
        val shell = activeShell(context) ?: return false
        val i = Intent().apply {
            setClassName(shell.pkg, "$shell.pkg.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", shell.bash)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        true
    }.getOrDefault(false)

    /** Find a working vite binary for [dir]. pnpm workspaces hoist vite to the
     *  workspace root, so try that first, then the app-local copy. */
    private fun findVite(dir: String): String {
        val candidates = listOf(
            "$dir/../../node_modules/.bin/vite",   // workspace root (hoisted)
            "$dir/../node_modules/.bin/vite",      // apps/ shared
            "$dir/node_modules/.bin/vite",         // local (bundler-only installs)
        )
        val hit = candidates.firstOrNull { java.io.File(it).exists() }
        return hit ?: candidates.first()
    }
}
