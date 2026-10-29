package com.axzora.os.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Over-the-air updates.
 *
 * The whole point: nobody should have to be sent an APK by hand every time
 * something changes. On launch the app asks the server what the current build
 * is; if it's newer than what's installed, the user is offered a one-tap
 * update — download and Android's own installer, nothing sideloaded manually.
 *
 * Android reserves app installation for a permission the user grants once
 * ("install unknown apps" for Axzora). Until then the offer opens that
 * settings screen instead, because an install that silently does nothing is
 * worse than one that explains itself.
 */
class Updater(
    private val context: Context,
    private val baseUrls: () -> List<String>,
) {
    /** The address that answered /update/check — reused for the download. */
    private var workingBase: String? = null

    data class Available(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
    )

    /** The current installed build's versionCode. */
    private val installedCode: Long
        get() = runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)

    /** @return an update if one is newer than what's installed, else null. */
    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        // Probe every configured address. The stored server URL can be blank or
        // point at an old IP (the backend machine's IP may have changed), so we
        // try the known candidates too and use whichever answers.
        val urls = baseUrls().map { "${normalize(it).trimEnd('/')}/update/check" }.distinct()
        for (url in urls) {
            android.util.Log.i("AxzoraUpdate", "check url=$url installed=$installedCode")
            val found = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                if (c.responseCode != 200) return@runCatching null
                val o = JSONObject(c.inputStream.bufferedReader().readText())
                c.disconnect()
                android.util.Log.i("AxzoraUpdate", "server said $o")
                if (!o.optBoolean("ok")) return@runCatching null
                val code = o.optInt("versionCode")
                if (code <= installedCode) return@runCatching null
                // Prefer this base for the download.
                workingBase = url.removeSuffix("/update/check")
                Available(code, o.optString("versionName"), o.optString("notes"))
            }.getOrElse {
                android.util.Log.w("AxzoraUpdate", "check failed on $url", it)
                null
            }
            if (found != null) return@withContext found
        }
        null
    }

    /**
     * Downloads the new APK and hands it to Android's installer.
     *
     * @return an error string, or null on success (the installer takes over).
     */
    suspend fun downloadAndInstall(onProgress: (Float) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val apk = File(context.cacheDir, "axzora-update.apk")
            runCatching {
                val base = workingBase ?: baseUrls().firstOrNull { it.isNotBlank() } ?: ""
                val c = URL("${normalize(base).trimEnd('/')}/update/download").openConnection()
                    as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                if (c.responseCode !in 200..299) return@withContext "Download failed (${c.responseCode})"
                val total = c.contentLengthLong
                apk.outputStream().use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                c.disconnect()
                if (apk.length() < 1_000_000) { apk.delete(); return@withContext "Update file looks wrong" }

                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", apk,
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(install)
                null
            }.getOrElse {
                runCatching { apk.delete() }
                it.message ?: "Update failed"
            }
        }

    /** Ensures the server has a scheme so URL() never throws "no protocol". */
    private fun normalize(base: String): String {
        val s = base.trim()
        return if (s.isEmpty() || s.startsWith("http://") || s.startsWith("https://")) s
        else "http://$s"
    }
}
