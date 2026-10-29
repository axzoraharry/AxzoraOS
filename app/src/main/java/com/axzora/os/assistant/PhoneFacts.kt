package com.axzora.os.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the phone can answer about itself, with no model and no network.
 *
 * A cloud model knows the world but nothing about *this* phone; these are
 * the questions it could never answer — how much battery is left, how many
 * contacts there are, what today's date is. Answering them locally is both
 * faster and more private than sending them anywhere.
 */
class PhoneFacts(private val context: Context) {

    fun answer(raw: String): String? {
        val t = raw.trim().lowercase().trim('.', '!', '?', ' ')
        if (t.isBlank()) return null

        // ---- battery ----
        if (t.containsAny("battery", "charge", "charged", "power left")) {
            val pct = batteryPercent() ?: return "I couldn't read the battery level."
            val charging = isCharging()
            return buildString {
                append("Battery is at $pct%")
                append(if (charging) ", and charging." else ".")
                if (!charging && pct <= 15) append(" Worth finding a charger.")
            }
        }

        // ---- storage ----
        if (t.containsAny("storage", "space left", "free space", "disk", "memory left")) {
            val (freeGb, totalGb) = storageGb()
            return "About ${freeGb}GB free of ${totalGb}GB."
        }

        // ---- time and date ----
        if (t.containsAny("what time", "the time", "time is it", "time now")) {
            return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        }
        if (t.containsAny("what day", "what date", "today's date", "date today", "what is today")) {
            return "Today is " +
                SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + "."
        }
        if (t.containsAny("tomorrow")) {
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DATE, 1) }
            return "Tomorrow is " +
                SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.time) + "."
        }

        // ---- contacts ----
        if (t.containsAny("how many contacts", "contacts do i have", "number of contacts")) {
            val n = contactCount() ?: return "I couldn't read your contacts."
            return "You have $n contacts saved."
        }

        // ---- apps ----
        if (t.containsAny("how many apps", "apps installed", "apps do i have")) {
            val n = runCatching {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                context.packageManager.queryIntentActivities(i, 0).size
            }.getOrNull() ?: return null
            return "There are $n apps on this phone."
        }

        // ---- the assistant's own nature ----
        if (t.containsAny("do you need internet", "work offline", "are you offline", "need wifi")) {
            return "The everyday things — opening apps, memory, sums, the phone's own " +
                "state — all work with no signal at all. Only open questions need " +
                "either the on-device model or a connection."
        }
        if (t.containsAny("where do my messages go", "do you send my", "is this private", "privacy")) {
            return "What you say to me stays on the phone unless a question needs the " +
                "wider world. Nothing is stored on a server for the assistant to read."
        }

        return null
    }

    // ------------------------------------------------------------------ bits

    private fun batteryPercent(): Int? = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }.getOrNull()

    private fun isCharging(): Boolean = runCatching {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    private fun storageGb(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val free = stat.availableBytes / 1_000_000_000
        val total = stat.totalBytes / 1_000_000_000
        free to total
    }.getOrDefault(0L to 0L)

    private fun contactCount(): Int? = runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID), null, null, null,
        )?.use { it.count }
    }.getOrNull()
}

private fun String.containsAny(vararg needles: String) = needles.any { contains(it) }
