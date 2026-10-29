package com.axzora.os.appshield

import android.content.Context
import android.provider.Settings

/**
 * App Shield — auto-hide developer/debug/accessibility settings around launching a
 * "locked-down" app, then revert them when it closes. Same idea as SU_IMD
 * ("Shut up! it's my device"), reimplemented on-device for AxzoraOS.
 *
 * How it works: banking/payment apps sometimes refuse to run (or silently cripple
 * themselves) when they detect developer options, USB debugging, or an
 * accessibility service. This does the manual flip automatically:
 *   snapshot() → hide() → (you open the app) → revert()
 *
 * HONEST NOTES:
 *  - WRITE_SECURE_SETTINGS is a signature permission the app cannot self-grant. It
 *    must be granted ONCE via ADB or Shizuku:
 *      adb shell pm grant com.axzora.os android.permission.WRITE_SECURE_SETTINGS
 *    Without it every write throws SecurityException and [hide] reports that clearly.
 *  - This hides things from apps' detection — legitimate on your own device, but it
 *    does bypass a protection layer those apps enforce; use for your own apps only.
 *  - No root required, same as SU_IMD.
 */
object AppShield {

    // Raw settings keys (these are NOT public SDK constants — SU_IMD defines the
    // same strings; Android stores them in Settings.Global / Settings.Secure).
    private const val KEY_DEV = "development_settings_enabled"     // Global
    private const val KEY_ADB = "adb_enabled"                      // Global
    private const val KEY_ADB_WIFI = "adb_wifi_enabled"            // Global
    private const val KEY_A11Y = "accessibility_enabled"           // Secure
    private const val KEY_A11Y_SVC = "enabled_accessibility_services" // Secure

    data class Snapshot(
        val devEnabled: String?,       // "0"/"1" or null (unset)
        val adbEnabled: String?,
        val wirelessAdb: String?,
        val a11yEnabled: String?,
        val a11yServices: String?,
    )

    /** Capture current values so [revert] can put everything back exactly. */
    fun snapshot(ctx: Context): Snapshot {
        val r = ctx.contentResolver
        return Snapshot(
            devEnabled = Settings.Global.getString(r, KEY_DEV),
            adbEnabled = Settings.Global.getString(r, KEY_ADB),
            wirelessAdb = Settings.Global.getString(r, KEY_ADB_WIFI),
            a11yEnabled = Settings.Secure.getString(r, KEY_A11Y),
            a11yServices = Settings.Secure.getString(r, KEY_A11Y_SVC),
        )
    }

    /** Hide the settings a locked-down app objects to. Returns "" on success. */
    fun hide(ctx: Context): String {
        val r = ctx.contentResolver
        return write(
            r,
            true to KEY_DEV to "0",
            true to KEY_ADB to "0",
            true to KEY_ADB_WIFI to "0",
            false to KEY_A11Y to "0",
            false to KEY_A11Y_SVC to "",
        )
    }

    /** Put the exact previous values back (the reverse of [hide]). */
    fun revert(ctx: Context, snap: Snapshot): String {
        val r = ctx.contentResolver
        return write(
            r,
            true to KEY_DEV to (snap.devEnabled ?: "1"),
            true to KEY_ADB to (snap.adbEnabled ?: "1"),
            true to KEY_ADB_WIFI to (snap.wirelessAdb ?: "0"),
            false to KEY_A11Y to (snap.a11yEnabled ?: "1"),
            false to KEY_A11Y_SVC to (snap.a11yServices ?: ""),
        )
    }

    /** True when WRITE_SECURE_SETTINGS is held (a write actually succeeds). */
    fun canWrite(ctx: Context): Boolean {
        val r = ctx.contentResolver
        return try {
            val cur = Settings.Global.getString(r, KEY_DEV) ?: "0"
            Settings.Global.putString(r, KEY_DEV, cur)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Throwable) {
            false
        }
    }

    /** The one ADB command that grants what this needs (shown in the UI). */
    const val GRANT_COMMAND =
        "adb shell pm grant com.axzora.os android.permission.WRITE_SECURE_SETTINGS"

    // ---- shield & launch ----------------------------------------------------
    // Snapshots the current settings, hides them, launches the target app, and
    // persists the snapshot so a Revert (notification action / reopen) restores.

    private const val PREFS = "axzora_appshield"

    /** A "shielded" session in flight, persisted so Revert works after a restart. */
    data class ActiveShield(val packageName: String, val label: String, val snap: Snapshot)

    /** Persist the snapshot of the active shield, keyed by nothing (one at a time). */
    private fun storeActive(ctx: Context, active: ActiveShield) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putString("pkg", active.packageName)
            .putString("label", active.label)
            .putString("dev", active.snap.devEnabled)
            .putString("adb", active.snap.adbEnabled)
            .putString("wadb", active.snap.wirelessAdb)
            .putString("a11y", active.snap.a11yEnabled)
            .putString("a11ysvc", active.snap.a11yServices)
            .apply()
    }

    /** Read back the active shield, or null when none is stored. */
    fun activeShield(ctx: Context): ActiveShield? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("pkg")) return null
        val snap = Snapshot(
            devEnabled = p.getString("dev", null),
            adbEnabled = p.getString("adb", null),
            wirelessAdb = p.getString("wadb", null),
            a11yEnabled = p.getString("a11y", null),
            a11yServices = p.getString("a11ysvc", null),
        )
        return ActiveShield(p.getString("pkg", "")!!, p.getString("label", "") ?: "", snap)
    }

    private fun clearActive(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Shield-and-launch: snapshot → hide → launch [packageName]. Returns "" on
     * success, or an error message (most often the missing-permission one).
     */
    fun shieldAndLaunch(ctx: Context, packageName: String, label: String): String {
        val snap = snapshot(ctx)
        val err = hide(ctx)
        if (err.isNotBlank()) return err
        storeActive(ctx, ActiveShield(packageName, label, snap))
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            // could not launch — put things back rather than leaving them hidden
            revert(ctx, snap)
            clearActive(ctx)
            return "Couldn't launch $label"
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return ""
    }

    /** Revert the active shield if one exists; returns "" (reverted) or a message. */
    fun revertActive(ctx: Context): String {
        val active = activeShield(ctx) ?: return "No active shield to revert."
        val err = revert(ctx, active.snap)
        if (err.isNotBlank()) return err
        clearActive(ctx)
        return ""
    }

    /** Write each (global?, key) → value; first failure wins, else "" = all OK. */
    private fun write(
        r: android.content.ContentResolver,
        vararg items: Pair<Pair<Boolean, String>, String>,
    ): String {
        for ((where, value) in items) {
            val (global, key) = where
            try {
                if (global) Settings.Global.putString(r, key, value)
                else Settings.Secure.putString(r, key, value)
            } catch (e: SecurityException) {
                return "No WRITE_SECURE_SETTINGS permission. Grant once: $GRANT_COMMAND"
            } catch (e: Throwable) {
                return "Failed on $key: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return ""
    }
}
