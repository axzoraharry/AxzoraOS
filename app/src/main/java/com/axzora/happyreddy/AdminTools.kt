package com.axzora.happyreddy

import android.content.Context
import com.axzora.os.data.SettingsStore
import com.axzora.os.wallet.AdminNotify
import com.axzora.os.wallet.AdminUser
import com.axzora.os.wallet.LedgerClient
import com.axzora.os.wallet.WalletStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Bridge from the (Java) [DeviceActions] tool dispatcher to the Kotlin wallet /
 * admin clients, so Mr Happy can perform admin actions (see the overview, push
 * a notification, mint Happy Paisa) through the same code path the admin
 * console uses.
 *
 * Every call is authenticated server-side by adminGate: the code must match
 * AND this device must hold an admin seat. The code is read from the on-device
 * wallet store (set when the operator unlocked the console) and is NEVER sent
 * to the model — the model just names the tool/arguments, and the code is
 * injected here. So the AI cannot learn or guess the code.
 */
object AdminTools {

    /** True if this device has a stored admin code (i.e. the console was unlocked). */
    @JvmStatic
    fun hasCode(ctx: Context): Boolean = runBlocking {
        WalletStore(ctx).adminCode().isNotBlank()
    }

    /**
     * Reads the stored admin code + device UID. Returns null when the console
     * was never unlocked on this device, so the caller can tell the user to
     * open the console first rather than failing ambiguously.
     */
    private fun creds(ctx: Context): Pair<String, String>? = runBlocking {
        val w = WalletStore(ctx)
        val code = w.adminCode()
        if (code.isBlank()) return@runBlocking null
        code.trim() to w.ensureUid()
    }

    // --- overview -----------------------------------------------------------

    /** The admin overview, formatted for a spoken reply. */
    @JvmStatic
    fun overview(ctx: Context): String {
        val c = creds(ctx) ?: return NOT_ADMIN
        val base = baseUrl(ctx)
        val ov = runBlocking { AdminNotify { base }.overview(c.first, c.second) }
            ?: return "I couldn't reach the server, or the admin code was rejected."
        val t = ov.totals
        val sb = StringBuilder()
        sb.append(String.format(Locale.ROOT,
            "%d user(s), %d circle(s), %.0f Happy Paisa in play.\n",
            t.users, t.circles, t.happyPaisa))
        for (u in ov.people.take(12)) sb.append(describeUser(u))
        if (ov.people.size > 12) sb.append("… and ${ov.people.size - 12} more.\n")
        return sb.toString().trim()
    }

    private fun describeUser(u: AdminUser): String {
        val name = u.name.ifBlank { u.uid }
        val hp = String.format(Locale.ROOT, "%.0f HP", u.balanceHp)
        val since = if (u.seen > 0) "seen ${ago(u.seen)}" else "not seen yet"
        return "• $name ($hp, $since)\n"
    }

    private fun ago(ts: Long): String {
        val mins = (System.currentTimeMillis() - ts) / 60000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    // --- notify -------------------------------------------------------------

    @JvmStatic
    fun notify(ctx: Context, target: String, title: String, text: String): String {
        val c = creds(ctx) ?: return NOT_ADMIN
        val base = baseUrl(ctx)
        val tgt = target.trim().ifBlank { "ALL" }
        val result = runBlocking {
            AdminNotify { base }.send(c.first, c.second, title.ifBlank { "Axzora" }, text, tgt)
        }
        return when (result) {
            is com.axzora.os.wallet.NotifyResult.Sent ->
                "Sent to ${result.delivered} device(s)."
            is com.axzora.os.wallet.NotifyResult.NotAdmin ->
                "The server said the admin code isn't accepted."
            is com.axzora.os.wallet.NotifyResult.NoSuchId ->
                "No Axzora user with that ID. Use 'ALL' or a valid ID."
            is com.axzora.os.wallet.NotifyResult.Failed -> result.reason
        }
    }

    // --- mint ---------------------------------------------------------------

    /** Mints [hp] Happy Paisa to an Axzora user by UID. */
    @JvmStatic
    fun mint(ctx: Context, uid: String, hp: Double): String {
        val c = creds(ctx) ?: return NOT_ADMIN
        if (uid.isBlank()) return "I need the Axzora user's ID to mint to."
        if (hp <= 0) return "Mint amount must be positive."
        val base = baseUrl(ctx)
        val result = runBlocking {
            LedgerClient { base }.mint(uid.trim().uppercase(), hp, c.first, c.second)
        }
        return when (result) {
            is com.axzora.os.wallet.LedgerResult.Ok ->
                "Minted $hp HP to $uid. New balance: ${result.balanceHp} HP."
            is com.axzora.os.wallet.LedgerResult.NotAdmin ->
                "The server rejected the admin code for that mint."
            is com.axzora.os.wallet.LedgerResult.NoSuchId ->
                "No Axzora user with ID $uid."
            is com.axzora.os.wallet.LedgerResult.Insufficient ->
                "Not enough Happy Paisa for that mint."
            is com.axzora.os.wallet.LedgerResult.AlreadySettled ->
                "That voucher was already settled."
            is com.axzora.os.wallet.LedgerResult.Offline -> result.reason
        }
    }

    private fun baseUrl(ctx: Context): String = runBlocking {
        SettingsStore(ctx).settings.first().serverUrl
    }

    private const val NOT_ADMIN =
        "You don't have the admin code on this device. Open the Admin console " +
            "(Settings, tap the version line 7 times) and unlock it first."
}
