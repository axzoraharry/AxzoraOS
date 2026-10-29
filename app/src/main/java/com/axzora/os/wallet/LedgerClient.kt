package com.axzora.os.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface LedgerResult {
    data class Ok(val balanceHp: Double, val toName: String = "") : LedgerResult
    data class Insufficient(val balanceHp: Double) : LedgerResult
    data object NoSuchId : LedgerResult
    data object NotAdmin : LedgerResult
    data class AlreadySettled(val by: String) : LedgerResult
    data class Offline(val reason: String) : LedgerResult
}

/** Result of a staking call (6% APR, 30-day lock). */
sealed interface StakeResult {
    data class Ok(
        val stakedHp: Double,
        val rewardHp: Double,
        val apr: Double,
        val lockDays: Int,
        val unlockAt: Long,
        val locked: Boolean,
        val balanceHp: Double,
    ) : StakeResult
    data object NoStake : StakeResult
    data class Locked(val unlockAt: Long) : StakeResult
    data class AlreadyStaked(val stakedHp: Double) : StakeResult
    data class Failed(val reason: String) : StakeResult
}

/**
 * Talks to the authoritative Happy Paisa ledger.
 *
 * The phone keeps its own balance for speed and for offline use, but the
 * server's number is the one that counts: it survives a reinstall, and it is
 * what makes a transfer atomic rather than two independent edits that can
 * disagree.
 */
class LedgerClient(
    /**
     * Where the HP authorisation token comes from. The bridge on :8002 hands it
     * out over loopback only, so it never leaves the device.
     */
    private val tokenUrl: () -> String = { "http://127.0.0.1:8002/api/token" },
    private val baseUrl: () -> String,
) {

    @Volatile
    private var cachedToken: String? = null

    /**
     * Moving HP requires `x-axzora-token`. This class used to send no such
     * header, so transfer / stake / unstake / settle all came back
     * 401 AUTH_REQUIRED — HP could be spent in a browser but not in the app.
     */
    private suspend fun authToken(): String? = withContext(Dispatchers.IO) {
        cachedToken?.let { return@withContext it }
        val t = runCatching {
            val c = (URL(tokenUrl()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            JSONObject(c.inputStream.bufferedReader().readText()).optString("token", "")
        }.getOrNull()
        t?.takeIf { it.isNotBlank() }?.also { cachedToken = it }
    }

    /** Forget a token the server rejected so the next call fetches a fresh one. */
    private fun forgetToken() { cachedToken = null }

    private fun open(
        path: String,
        method: String,
        body: JSONObject? = null,
        token: String? = null,
    ): HttpURLConnection {
        val c = URL("${baseUrl().trimEnd('/')}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 7000
        c.readTimeout = 9000
        if (token != null) c.setRequestProperty(TOKEN_HEADER, token)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return c
    }

    companion object {
        private const val TOKEN_HEADER = "x-axzora-token"
        private const val NO_TOKEN =
            "HP authorisation unavailable — the bridge on :8002 returned no token, so the ledger refused the call"
    }

    private fun HttpURLConnection.readJson(): JSONObject {
        val stream = if (responseCode in 200..299) inputStream else errorStream
        return JSONObject(stream.bufferedReader().readText())
    }

    /** Null when the server can't be reached — caller keeps the local figure. */
    suspend fun balance(uid: String): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open("/ledger/balance?uid=" + URLEncoder.encode(uid, "UTF-8"), "GET")
            if (c.responseCode != 200) return@runCatching null
            c.readJson().optDouble("balanceHp", 0.0)
        }.getOrNull()
    }

    suspend fun transfer(from: String, to: String, hp: Double, note: String): LedgerResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val tk = authToken()
                val c = open(
                    "/ledger/transfer", "POST",
                    JSONObject().put("from", from).put("to", to).put("hp", hp).put("note", note),
                    token = tk,
                )
                val o = c.readJson()
                when (c.responseCode) {
                    200 -> LedgerResult.Ok(o.optDouble("balanceHp", 0.0), o.optString("toName"))
                    401 -> { forgetToken(); LedgerResult.Offline(if (tk == null) NO_TOKEN else o.optString("error", "authorisation rejected")) }
                    404 -> LedgerResult.NoSuchId
                    409 -> LedgerResult.Insufficient(o.optDouble("balanceHp", 0.0))
                    else -> LedgerResult.Offline(o.optString("error", "server said ${c.responseCode}"))
                }
            }.getOrElse { LedgerResult.Offline(it.message ?: "network error") }
        }

    suspend fun mint(uid: String, hp: Double, adminCode: String, deviceId: String): LedgerResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = open(
                    "/ledger/mint", "POST",
                    JSONObject().put("uid", uid).put("hp", hp)
                        .put("adminCode", adminCode).put("deviceId", deviceId),
                )
                val o = c.readJson()
                when (c.responseCode) {
                    200 -> LedgerResult.Ok(o.optDouble("balanceHp", 0.0))
                    409 -> LedgerResult.Offline(o.optString("error", "admin is active on another device"))
                    403 -> LedgerResult.NotAdmin
                    else -> LedgerResult.Offline(o.optString("error", "server said ${c.responseCode}"))
                }
            }.getOrElse { LedgerResult.Offline(it.message ?: "network error") }
        }

    /**
     * Banks an offline voucher once connectivity returns. The nonce is what
     * catches a voucher that was handed to two people while both were offline.
     */
    suspend fun settle(uid: String, nonce: String, hp: Double, from: String): LedgerResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val tk = authToken()
                val c = open(
                    "/ledger/settle", "POST",
                    JSONObject().put("uid", uid).put("nonce", nonce).put("hp", hp).put("from", from),
                    token = tk,
                )
                val o = c.readJson()
                when (c.responseCode) {
                    200 -> LedgerResult.Ok(o.optDouble("balanceHp", 0.0))
                    401 -> { forgetToken(); LedgerResult.Offline(if (tk == null) NO_TOKEN else o.optString("error", "authorisation rejected")) }
                    409 -> LedgerResult.AlreadySettled(o.optString("settledBy"))
                    else -> LedgerResult.Offline(o.optString("error", "server said ${c.responseCode}"))
                }
            }.getOrElse { LedgerResult.Offline(it.message ?: "network error") }
        }

    // ---- staking (6% APR, 30-day lock) ----

    /** Current staking position + accrued reward for [uid]. */
    suspend fun staking(uid: String): StakeResult = withContext(Dispatchers.IO) {
        runCatching {
            val c = open("/ledger/staking?uid=" + URLEncoder.encode(uid, "UTF-8"), "GET")
            val o = c.readJson()
            when (c.responseCode) {
                200 -> StakeResult.Ok(
                    stakedHp = o.optDouble("stakedHp", 0.0),
                    rewardHp = o.optDouble("rewardHp", 0.0),
                    apr = o.optDouble("apr", 0.0),
                    lockDays = o.optInt("lockDays", 30),
                    unlockAt = o.optLong("unlockAt", 0),
                    locked = o.optBoolean("locked", false),
                    balanceHp = o.optDouble("balanceHp", 0.0),
                )
                404 -> StakeResult.NoStake
                else -> StakeResult.Failed(o.optString("error", "server said ${c.responseCode}"))
            }
        }.getOrElse { StakeResult.Failed(it.message ?: "network error") }
    }

    /** Lock [hp] HP into staking. */
    suspend fun stake(uid: String, hp: Double): StakeResult = withContext(Dispatchers.IO) {
        runCatching {
            val tk = authToken()
            val c = open("/ledger/stake", "POST", JSONObject().put("uid", uid).put("hp", hp), token = tk)
            val o = c.readJson()
            when (c.responseCode) {
                200 -> StakeResult.Ok(
                    stakedHp = o.optDouble("stakedHp", hp),
                    rewardHp = 0.0,
                    apr = o.optDouble("apr", 0.0),
                    lockDays = o.optInt("lockDays", 30),
                    unlockAt = o.optLong("unlockAt", 0),
                    locked = true,
                    balanceHp = o.optDouble("balanceHp", 0.0),
                )
                401 -> { forgetToken(); StakeResult.Failed(if (tk == null) NO_TOKEN else o.optString("error", "authorisation rejected")) }
                409 -> if (o.optString("error").contains("already staked"))
                        StakeResult.AlreadyStaked(o.optDouble("stakedHp", 0.0))
                    else StakeResult.Failed(o.optString("error", "insufficient balance"))
                404 -> StakeResult.NoStake
                else -> StakeResult.Failed(o.optString("error", "server said ${c.responseCode}"))
            }
        }.getOrElse { StakeResult.Failed(it.message ?: "network error") }
    }

    /** Return the stake + reward once the lock period has passed. */
    suspend fun unstake(uid: String): StakeResult = withContext(Dispatchers.IO) {
        runCatching {
            val tk = authToken()
            val c = open("/ledger/unstake", "POST", JSONObject().put("uid", uid), token = tk)
            val o = c.readJson()
            when (c.responseCode) {
                200 -> StakeResult.Ok(
                    stakedHp = 0.0,
                    rewardHp = o.optDouble("rewardHp", 0.0),
                    apr = 0.0, lockDays = 0, unlockAt = 0, locked = false,
                    balanceHp = o.optDouble("balanceHp", 0.0),
                )
                401 -> { forgetToken(); StakeResult.Failed(if (tk == null) NO_TOKEN else o.optString("error", "authorisation rejected")) }
                409 -> StakeResult.Locked(o.optLong("unlockAt", 0))
                404 -> StakeResult.NoStake
                else -> StakeResult.Failed(o.optString("error", "server said ${c.responseCode}"))
            }
        }.getOrElse { StakeResult.Failed(it.message ?: "network error") }
    }
}
