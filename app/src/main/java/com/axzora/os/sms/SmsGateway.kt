package com.axzora.os.sms

/**
 * The Mr Happy Communication Layer gateway.
 *
 * Turns an inbound SMS into a typed action the rest of the system acts on:
 *   - an HP message  -> bank it into the wallet (via [onBankHp], which the
 *                       app wires to WalletStore.credit(..., Rail.SMS))
 *   - an IMPACT      -> convert a verified mission into a reward (via [onReward],
 *                       which the app wires to AdminTools.mint + impact score)
 *
 * Dependency-free and interface-driven so it is unit-testable without Android
 * SMS or wallet classes. Transport side effects (SmsManager.sendTextMessage for
 * sending, an SMS_RECEIVED BroadcastReceiver for receiving) live outside this
 * file and call into it.
 */
class SmsGateway(
    private val onBankHp: (SysBankHp) -> Unit = {},
    private val onReward: (SysReward) -> Unit = {},
) {

    /** The ordered set of communication rails, for resilient fallback. */
    enum class CommsChannel { INTERNET, WIFI_MESH, BLUETOOTH, SMS, QR }

    /** A recent inbound appliance. */
    data class Sms(val from: String, val body: String)

    /** The decision produced for an inbound SMS. */
    sealed class Decision {
        data class BankHp(val amount: Double, val from: String, val note: String) : Decision()
        data class Reward(val missionId: String, val location: String, val amount: Double) : Decision()
        object Ignore : Decision()
    }

    /** A system side-effect (not part of the SMS wire format). */
    data class SysBankHp(val amount: Double, val from: String, val note: String)

    data class SysReward(val missionId: String, val location: String, val amount: Double)

    /**
     * Decide what to do with a received SMS. Returns the decision (so tests and
     * the UI can inspect it) and fires the matching side-effect callback.
     */
    fun handle(sms: Sms): Decision {
        return when (val m = SmsProtocol.parse(sms.body)) {
            is SmsProtocol.Message.Hp -> {
                val d = Decision.BankHp(m.amount, m.fromId.ifBlank { sms.from }, m.note)
                onBankHp(SysBankHp(m.amount, m.fromId.ifBlank { sms.from }, m.note))
                d
            }

            is SmsProtocol.Message.Impact -> {
                val d = if (m.status == "COMPLETE") {
                    Decision.Reward(m.missionId, m.location, m.reward)
                } else {
                    Decision.Ignore
                }
                if (d is Decision.Reward) onReward(SysReward(m.missionId, m.location, m.reward))
                d
            }

            SmsProtocol.Message.Unknown -> Decision.Ignore
        }
    }

    /**
     * Resilient communication-order fallback (the "Mr Happy Emergency Mode"
     * idea): pick the best rail for the current connectivity. Returns the rails
     * to try, best first.
     */
    fun fallbackOrder(
        online: Boolean,
        wifiMesh: Boolean,
        bluetooth: Boolean,
    ): List<CommsChannel> {
        val order = mutableListOf<CommsChannel>()
        if (online) order += CommsChannel.INTERNET
        if (wifiMesh) order += CommsChannel.WIFI_MESH
        if (bluetooth) order += CommsChannel.BLUETOOTH
        order += CommsChannel.SMS
        order += CommsChannel.QR
        return order
    }

    /** First usable rail for the current connectivity, or [CommsChannel.SMS]. */
    fun primaryRail(
        online: Boolean,
        wifiMesh: Boolean,
        bluetooth: Boolean,
    ): CommsChannel = fallbackOrder(online, wifiMesh, bluetooth).firstOrNull()
        ?: CommsChannel.SMS

    /**
     * Send a Happy Paisa transfer (or any Mr Happy message) over the injected
     * transport. Kept interface-driven ([sendText] is provided by the app) so
     * this is unit-testable without SmsManager; the app wires
     * `sendText = { n, t -> SmsManager.getDefault().sendTextMessage(n, null, t, null, null) }`.
     * @returns true if the transport accepted the message.
     */
    fun send(
        number: String,
        body: String,
        sendText: (number: String, body: String) -> Boolean,
    ): Boolean {
        if (number.isBlank() || body.isBlank()) return false
        return runCatching { sendText(number.trim(), body) }.getOrDefault(false)
    }

    /** Build the SMS body for an HP transfer, ready to send. */
    fun buildTransferSms(amount: Double, toId: String, fromId: String = "", note: String = ""): String =
        SmsProtocol.encodeHp(amount, toId, fromId, note)

    /** Build the SMS body for an impact/mission verification. */
    fun buildImpactSms(missionId: String, status: String, location: String, reward: Double): String =
        SmsProtocol.encodeImpact(missionId, status, location, reward)
}
