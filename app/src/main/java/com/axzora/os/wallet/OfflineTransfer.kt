package com.axzora.os.wallet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import org.json.JSONObject

/**
 * A signed-ish transfer voucher moved between phones without a server.
 *
 * Encoded as compact JSON so it fits a QR code, a Bluetooth frame, or an NFC
 * NDEF record. There is no cryptographic signature yet: until the blockchain
 * ledger exists, an offline voucher is only as trustworthy as the person
 * handing it over, and nothing prevents the same voucher being presented
 * twice. The UI states this plainly before an offline payment is accepted.
 */
data class Voucher(
    val hp: Double,
    val from: String,
    val note: String,
    val nonce: String,
    val ts: Long,
) {
    fun encode(): String = JSONObject()
        .put("v", 1)
        .put("hp", hp)
        .put("from", from)
        .put("note", note)
        .put("nonce", nonce)
        .put("ts", ts)
        .toString()

    companion object {
        fun create(hp: Double, from: String, note: String) = Voucher(
            hp = hp,
            from = from.ifBlank { "Unknown" },
            note = note,
            nonce = java.util.UUID.randomUUID().toString().take(12),
            ts = System.currentTimeMillis(),
        )

        /** Null when the text isn't a valid Axzora voucher. */
        fun decode(raw: String): Voucher? = runCatching {
            val o = JSONObject(raw.trim())
            require(o.optInt("v") == 1) { "unsupported voucher version" }
            val hp = o.getDouble("hp")
            require(hp > 0) { "non-positive amount" }
            Voucher(
                hp = hp,
                from = o.optString("from", "Unknown"),
                note = o.optString("note"),
                nonce = o.optString("nonce"),
                ts = o.optLong("ts"),
            )
        }.getOrNull()
    }
}

/** What offline rails this particular handset actually supports. */
data class RailSupport(
    val bluetooth: Boolean,
    val bluetoothOn: Boolean,
    val nfc: Boolean,
    val nfcOn: Boolean,
)

object OfflineRails {
    fun detect(context: Context): RailSupport {
        val btAdapter: BluetoothAdapter? = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

        val hasNfcFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
        val nfcAdapter = if (hasNfcFeature) runCatching {
            NfcAdapter.getDefaultAdapter(context)
        }.getOrNull() else null

        return RailSupport(
            bluetooth = btAdapter != null,
            bluetoothOn = runCatching { btAdapter?.isEnabled == true }.getOrDefault(false),
            nfc = nfcAdapter != null,
            nfcOn = runCatching { nfcAdapter?.isEnabled == true }.getOrDefault(false),
        )
    }
}
