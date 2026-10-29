package com.axzora.os.wallet

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.axzora.os.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.walletStore by preferencesDataStore("axzora_wallet")

/** How a payment travelled between devices. */
enum class Rail { INTERNET, BLUETOOTH, NFC, QR, SMS, SYSTEM }

/** One wallet movement. Positive [hp] is money in, negative is money out. */
data class Txn(
    val id: String,
    val hp: Double,
    val party: String,
    val note: String,
    val rail: Rail,
    val ts: Long,
) {
    val isCredit get() = hp >= 0

}

/** Snapshot of the wallet. */
data class WalletState(
    val balanceHp: Double = 0.0,
    val isAdmin: Boolean = false,
    val txns: List<Txn> = emptyList(),
    val redeemed: Set<String> = emptySet(),
    val uid: String = "",
) {

}

/**
 * Happy Paisa wallet — one global unit, displayed in whichever currency the
 * user prefers (see [Currencies]).
 *
 * This is the device's local copy. The authoritative balance lives on the
 * server ledger ([LedgerClient]); the local figure exists so the wallet is
 * instant to open and still usable with no signal, and is reconciled against
 * the server whenever one is reachable.
 *
 * Offline transfers (Bluetooth / NFC / QR) debit the sender and credit the
 * receiver immediately. Each voucher carries a nonce, and the ledger refuses a
 * nonce it has already seen, so a voucher handed to two people while both were
 * offline is caught the moment either of them reconnects.
 */
class WalletStore(private val context: Context) {

    private object Keys {
        val BALANCE = doublePreferencesKey("balance_hp")
        val ADMIN = booleanPreferencesKey("is_admin")
        val TXNS = stringPreferencesKey("txns")
        val REDEEMED = stringPreferencesKey("redeemed")
        val UID = stringPreferencesKey("axzora_uid")
        val CLAIMED = stringPreferencesKey("claimed_transfers")
        val ADMIN_CODE = stringPreferencesKey("admin_code")
    }

    val state: Flow<WalletState> = context.walletStore.data.map { p ->
        WalletState(
            balanceHp = p[Keys.BALANCE] ?: 0.0,
            isAdmin = p[Keys.ADMIN] ?: false,
            txns = parseTxns(p[Keys.TXNS]),
            redeemed = (p[Keys.REDEEMED] ?: "").split("|").filter { it.isNotBlank() }.toSet(),
            uid = p[Keys.UID] ?: "",
        )
    }

    suspend fun currentBalance(): Double = state.first().balanceHp

    /**
     * Reconciles with the ledger — but NON-destructively. The wallet is
     * offline-first and this device's balance is durable (DataStore survives
     * logout/login). The free-tier server can lose its ledger file on a
     * redeploy and report 0; syncing that down would wipe real Happy Paisa
     * (this is exactly what erased minted balances). So we only ever sync the
     * balance UP: incoming credits raise it, a reset/again-lower server never
     * lowers it. Spends are applied locally by debit(), which is authoritative
     * for money leaving this device.
     */
    suspend fun syncBalance(serverBalanceHp: Double) =
        context.walletStore.edit { p ->
            val local = p[Keys.BALANCE] ?: 0.0
            p[Keys.BALANCE] = maxOf(local, serverBalanceHp).coerceAtLeast(0.0)
        }

    /** Force the balance to an exact figure (used right after a mint, where
     *  the server value is fresh and authoritative and includes the new mint). */
    suspend fun setBalance(hp: Double) =
        context.walletStore.edit { it[Keys.BALANCE] = hp.coerceAtLeast(0.0) }

    /** This device's permanent Axzora ID, minted on first use. */
    suspend fun ensureUid(): String {
        var id = ""
        context.walletStore.edit { p ->
            id = p[Keys.UID] ?: AxzoraId.generate().also { p[Keys.UID] = it }
        }
        return id
    }

    /** True when this server transfer id was already banked. */
    suspend fun hasTransfer(transferId: Long): Boolean =
        claimedIds(context.walletStore.data.first()[Keys.CLAIMED]).contains(transferId)

    /**
     * Banks an incoming transfer exactly once. Re-delivery of the same id
     * (e.g. after a failed ack) is ignored instead of crediting twice.
     */
    suspend fun creditTransfer(transferId: Long, hp: Double, party: String, note: String) {
        context.walletStore.edit { p ->
            val seen = claimedIds(p[Keys.CLAIMED])
            if (transferId in seen) return@edit
            p[Keys.CLAIMED] = (seen + transferId).takeLast(500).joinToString(",")
            p[Keys.BALANCE] = (p[Keys.BALANCE] ?: 0.0) + kotlin.math.abs(hp)
            val arr = runCatching { JSONArray(p[Keys.TXNS] ?: "[]") }.getOrDefault(JSONArray())
            val entry = JSONObject()
                .put("id", "t$transferId")
                .put("hp", kotlin.math.abs(hp))
                .put("party", party)
                .put("note", note.ifBlank { "Received" })
                .put("rail", Rail.INTERNET.name)
                .put("ts", System.currentTimeMillis())
            val out = JSONArray().put(entry)
            for (i in 0 until minOf(arr.length(), 99)) out.put(arr.getJSONObject(i))
            p[Keys.TXNS] = out.toString()
        }
    }

    private fun claimedIds(raw: String?): List<Long> =
        (raw ?: "").split(",").mapNotNull { it.trim().toLongOrNull() }

    /**
     * Reveals the admin controls and keeps the code so later mints can be
     * authorised. This is only a UI gate — the ledger re-checks the code on
     * every mint, so unlocking here grants nothing on its own.
     */
    suspend fun unlockAdmin(code: String) {
        context.walletStore.edit {
            it[Keys.ADMIN] = true
            it[Keys.ADMIN_CODE] = code.trim()
        }
    }

    /**
     * The code this device has been given, or blank.
     *
     * It used to fall back to a built-in constant when nothing was stored,
     * which handed every install a working admin code — opening the console
     * auto-authenticated and the operator screen appeared for everyone. A
     * device that has not been told the code now has nothing to send.
     */
    suspend fun adminCode(): String =
        context.walletStore.data.first()[Keys.ADMIN_CODE].orEmpty()

    /** Lets an admin correct a code that the server rejects. */
    suspend fun clearAdmin() = context.walletStore.edit {
        it[Keys.ADMIN] = false
        it[Keys.ADMIN_CODE] = ""
    }

    suspend fun credit(hp: Double, party: String, note: String, rail: Rail) =
        apply(hp = kotlin.math.abs(hp), party = party, note = note, rail = rail)

    /**
     * Debits [hp] if the balance covers it.
     * @return true when the payment was applied.
     */
    suspend fun debit(hp: Double, party: String, note: String, rail: Rail): Boolean {
        if (hp <= 0) return false
        val bal = state.first().balanceHp
        if (bal + 1e-9 < hp) return false
        apply(hp = -hp, party = party, note = note, rail = rail)
        return true
    }

    suspend fun markRedeemed(code: String) = context.walletStore.edit { p ->
        val cur = (p[Keys.REDEEMED] ?: "").split("|").filter { it.isNotBlank() }.toMutableSet()
        cur += code
        p[Keys.REDEEMED] = cur.joinToString("|")
    }

    private suspend fun apply(hp: Double, party: String, note: String, rail: Rail) {
        context.walletStore.edit { p ->
            p[Keys.BALANCE] = ((p[Keys.BALANCE] ?: 0.0) + hp).coerceAtLeast(0.0)
            val arr = runCatching { JSONArray(p[Keys.TXNS] ?: "[]") }.getOrDefault(JSONArray())
            val entry = JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("hp", hp)
                .put("party", party)
                .put("note", note)
                .put("rail", rail.name)
                .put("ts", System.currentTimeMillis())
            // newest first, keep 100
            val out = JSONArray().put(entry)
            for (i in 0 until minOf(arr.length(), 99)) out.put(arr.getJSONObject(i))
            p[Keys.TXNS] = out.toString()
        }
    }

    private fun parseTxns(raw: String?): List<Txn> = runCatching {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Txn(
                id = o.optString("id"),
                hp = o.optDouble("hp", 0.0),
                party = o.optString("party"),
                note = o.optString("note"),
                rail = runCatching { Rail.valueOf(o.optString("rail")) }.getOrDefault(Rail.SYSTEM),
                ts = o.optLong("ts"),
            )
        }
    }.getOrDefault(emptyList())
}
