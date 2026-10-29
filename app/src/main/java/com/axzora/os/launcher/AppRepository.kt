package com.axzora.os.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One launchable application on the device. */
data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap,
    /** First-install time, so the drawer can sort newest first. */
    val installedAt: Long = 0L,
) {
    /** Letters+digits only, lowercased — "YouTube Music" -> "youtubemusic". */
    val normalized: String = label.lowercase().filter { it.isLetterOrDigit() }
}

class AppRepository(private val context: Context) {

    /** Recently launched packages, most recent first. Persisted so the drawer can
     *  offer a "Recent" row that survives a reboot, and exposed as a state so the
     *  UI refreshes the moment an app is opened from anywhere (tiles or assistant). */
    private val prefs: SharedPreferences =
        context.getSharedPreferences("axzora_recent", Context.MODE_PRIVATE)

    private val _recentPackages = MutableStateFlow(loadRecentPackages())
    val recentPackages: StateFlow<List<String>> = _recentPackages.asStateFlow()

    /** Query every app that exposes a launcher activity, sorted alphabetically. */
    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null // hide ourselves

            // Label and icon are loaded independently: a failure in either must
            // never drop the app from the list, or it becomes unlaunchable by
            // name from the assistant.
            val label = runCatching { ri.loadLabel(pm).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg.substringAfterLast('.')

            val icon = runCatching {
                ri.activityInfo.loadIcon(pm).toBitmap(width = 144, height = 144).asImageBitmap()
            }.getOrElse { placeholderIcon() }

            val installed = runCatching {
                pm.getPackageInfo(pkg, 0).firstInstallTime
            }.getOrDefault(0L)

            AppInfo(label = label, packageName = pkg, icon = icon, installedAt = installed)
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(packageName: String): Boolean {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(launch); true }.getOrDefault(false)
        if (ok) recordRecent(packageName)
        return ok
    }

    /** Moves [packageName] to the front of the recent list, capped to [MAX_RECENT]. */
    private fun recordRecent(packageName: String) {
        val cur = _recentPackages.value.toMutableList()
        cur.remove(packageName)
        cur.add(0, packageName)
        // Keep only the most recent few — enough for a row, without a list that
        // grows forever in storage.
        if (cur.size > MAX_RECENT) cur.removeAt(MAX_RECENT)
        _recentPackages.value = cur
        prefs.edit().putString(RECENT_KEY, cur.joinToString("|")).apply()
    }

    private fun loadRecentPackages(): List<String> =
        prefs.getString(RECENT_KEY, "")?.split("|")?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * The apps a phone must be able to reach from its home screen.
     *
     * Resolved by asking Android who handles each job rather than by looking
     * for package names: the dialer is `com.samsung.android.dialer` on a
     * Samsung, `com.android.dialer` on a Pixel, something else again on a
     * Xiaomi. Asking the system means this works on any handset, and honours
     * a user who has set a third-party app as their default.
     */
    fun essentialPackages(): List<String> {
        val pm = context.packageManager
        val found = LinkedHashSet<String>()

        fun add(intent: Intent) {
            val pkg = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: return
            // The resolver returns a chooser stub when nothing is set as
            // default; that isn't an app the user can be sent to.
            if (pkg.isBlank() || pkg == "android" || pkg == context.packageName) return
            found += pkg
        }

        // Phone comes first — it is the one thing a phone cannot do without.
        add(Intent(Intent.ACTION_DIAL))
        // The user's chosen SMS app, which Android tracks separately.
        Telephony.Sms.getDefaultSmsPackage(context)?.let { if (it.isNotBlank()) found += it }
        add(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        add(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
        add(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        add(Intent(Intent.ACTION_VIEW).apply { type = "vnd.android.cursor.dir/contact" })

        // Only offer packages that actually have a launcher entry, or tapping
        // the tile would do nothing.
        return found.filter { pm.getLaunchIntentForPackage(it) != null }
    }

    /** Opens the phone's dialer, whichever app that is. */
    fun openDialer(): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    /** Opens the phone's SMS app, whichever app that is. */
    fun openSms(): Boolean = runCatching {
        val pkg = Telephony.Sms.getDefaultSmsPackage(context)
        val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** Plain grey square used when an app's real icon can't be decoded. */
    private fun placeholderIcon(): ImageBitmap {
        val bmp = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(0xFF555B6E.toInt())
        return bmp.asImageBitmap()
    }

    private companion object {
        private const val MAX_RECENT = 10
        private const val RECENT_KEY = "recent_packages"
    }
}
