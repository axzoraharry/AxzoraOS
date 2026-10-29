package com.axzora.os.pay

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.payStore by preferencesDataStore("axzora_pay")
private val KEY_HISTORY = stringPreferencesKey("history")

/** One payment attempt launched from Axzora Pay. */
data class PayEntry(
    val vpa: String,
    val name: String,
    val amount: String,
    val note: String,
    val ts: Long,
)

/**
 * Axzora Pay hands payments to the user's LICENSED UPI app (GPay, PhonePe,
 * Paytm…) via the standard upi://pay deep link. Axzora never touches money,
 * never sees a PIN, and cannot complete a transfer on its own — the user
 * always confirms inside their UPI app. This is by design and is also how
 * every legitimate merchant integration works.
 */
class PayManager(private val context: Context) {

    val history: Flow<List<PayEntry>> = context.payStore.data.map { p ->
        runCatching {
            val arr = JSONArray(p[KEY_HISTORY] ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PayEntry(
                    vpa = o.getString("vpa"),
                    name = o.optString("name"),
                    amount = o.optString("amount"),
                    note = o.optString("note"),
                    ts = o.optLong("ts"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Opens the system chooser with every installed UPI app able to handle
     * the payment. Returns false when no UPI app is installed.
     */
    fun launchUpi(vpa: String, name: String, amount: String, note: String): Boolean {
        val uri = ("upi://pay" +
            "?pa=" + android.net.Uri.encode(vpa.trim()) +
            "&pn=" + android.net.Uri.encode(name.ifBlank { vpa }.trim()) +
            (if (amount.isNotBlank()) "&am=" + android.net.Uri.encode(amount.trim()) else "") +
            "&cu=INR" +
            (if (note.isNotBlank()) "&tn=" + android.net.Uri.encode(note.take(50)) else "")
            ).toUri()

        val intent = Intent(Intent.ACTION_VIEW, uri)
        val chooser = Intent.createChooser(intent, "Pay with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(chooser)
            true
        } else false
    }

    suspend fun record(entry: PayEntry) {
        context.payStore.edit { p ->
            val arr = runCatching { JSONArray(p[KEY_HISTORY] ?: "[]") }.getOrDefault(JSONArray())
            val o = JSONObject()
                .put("vpa", entry.vpa).put("name", entry.name)
                .put("amount", entry.amount).put("note", entry.note)
                .put("ts", entry.ts)
            // newest first, cap at 50
            val out = JSONArray().put(o)
            for (i in 0 until minOf(arr.length(), 49)) out.put(arr.getJSONObject(i))
            p[KEY_HISTORY] = out.toString()
        }
    }
}
