package com.axzora.os

/**
 * Backend addresses, tried in order until one answers.
 *
 * A single hard-coded URL made the app brittle: a moved host or a new tunnel
 * meant rebuilding and reinstalling the APK on every phone. Instead the app
 * probes this list (see [com.axzora.os.net.ServerFinder]), keeps the first
 * server that responds, and re-probes when that one goes quiet — so the backend
 * can move without anyone reinstalling anything.
 *
 * Only list addresses that are actually live. An entry that never answers is
 * not harmless: it is probed on every sweep, and because [DEFAULT_SERVER] is
 * the first entry it also becomes what the app falls back to when discovery
 * fails — leaving a fresh install pointed at a server that cannot work.
 */
object Config {

    val SERVER_CANDIDATES = listOf(
        // THIS device hosts the backend (node server/server.js -> port 8787), so
        // loopback is the only address that reliably works.
        //
        // A hardcoded LAN entry used to sit here ("http://192.168.29.204:8787").
        // That host no longer exists — the device is on mobile data with only
        // `lo` and `rmnet0`, and the carrier address changes constantly — so the
        // probe burned a timeout on a dead host on every sweep. Add an address
        // here only when it is genuinely reachable.
        "http://127.0.0.1:8787",
    )

    /** Used until a probe succeeds, so it must be a real server. */
    val DEFAULT_SERVER = SERVER_CANDIDATES.first()

    /** Admin unlock code for minting Happy Paisa. Verified by the server. */
    /*
     * Deliberately no admin code here.
     *
     * It used to be a constant, which meant it shipped inside the APK: running
     * `strings` over classes2.dex printed it in full. Anyone could read it and
     * mint themselves unlimited Happy Paisa. The code now lives only on the
     * server, and the app simply forwards whatever the operator types.
     */

    /** 1 HP is worth this many INR. */
    const val HP_TO_INR = 1000.0

    /** USD value of 1 HP (peg ~ $11.55), for the wallet's dual-currency display. */
    const val HP_TO_USD = 11.55

    /** Happy Paisa staking (mirrors the HP DApp): 6% APR, 30-day lock. */
    const val STAKING_APR = 0.06
    const val STAKING_LOCK_DAYS = 30

    /**
     * The in-house voice assistant. When installed, the floating bubble and
     * the assistant gesture open it instead of the built-in chat — Mr Happy
     * is the assistant Axzora fronts; the built-in engine is the fallback
     * for phones that don't have it yet.
     */
    const val MR_HAPPY_PACKAGE = "com.axzora.happyreddy"

    /**
     * OpenRoom (MiniMax AI desktop) — served by Vite on port 3000 and loaded by
     * the OpenRoom tile. It runs through Termux's RUN_COMMAND, so these point at
     * the device's Termux-like shell, the project location, and the exact start
     * command. Adjust them if the project moves or the shell package changes.
     */
    const val OPENROOM_DIR =
        "/data/data/com.dsharnessmobile.shell/files/home/OpenRoom/apps/webuiapps"
    const val OPENROOM_TERMUX_PACKAGE = "com.dsharnessmobile.shell"
    const val OPENROOM_TERMUX_BASH = "/data/data/com.dsharnessmobile.shell/files/usr/bin/bash"
    const val OPENROOM_TERMUX_HOME = "/data/data/com.dsharnessmobile.shell/files/home"

    /**
     * DeepSeek drives Mr Happy's conversation + device control (tool calling).
     *
     * SECURITY: the API key is NOT shipped in the APK. It lives server-side in
     * `server/deepseek-key.json` (gitignored), and the app talks to the server's
     * `/ai/deepseek` proxy which attaches the key. This constant is only a legacy
     * fallback and must stay empty in any build you distribute.
     */
    const val DEEPSEEK_API_KEY = ""
    const val DEEPSEEK_MODEL = "deepseek-chat"

    /**
     * On-device model (llama.cpp llama-server, OpenAI-compatible) on the phone.
     * Mr Happy prefers this (offline, free, private) and falls back to cloud
     * DeepSeek if it's not running. Leave blank to go straight to DeepSeek.
     */
    const val LOCAL_BRAIN_URL = "http://127.0.0.1:8080/v1/chat/completions"

    /**
     * Bumped whenever the app starts needing a permission it didn't before.
     *
     * Permissions used to be asked only during first-run setup, so anyone who
     * *updated* never saw the new ones — the request simply never ran again.
     * Storing the revision the user was last shown lets an update ask for what
     * it added, without pestering people who are already up to date.
     */
    const val PERMISSION_REVISION = 2
}
