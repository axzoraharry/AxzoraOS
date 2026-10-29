package com.axzora.os.sms

/**
 * Applies a parsed [SmsGateway.Decision] to the real side-effects (the wallet,
 * minting, etc.) — the bridge the receiver uses. It is interface-driven (the
 * side-effects are injected) so it is unit-testable without the Android wallet
 * or AdminTools classes.
 *
 * Wiring in the app (on a real build):
 *   val sink = SmsActionSink(
 *       bankHp = { a, from, note -> scope.launch { wallet.credit(a, from, note, Rail.SMS) } },
 *       reward = { mission, loc, amt -> AdminTools.mint(null, mission, amt) },
 *   )
 *   receiver:  sink.process(SmsGateway.Sms(from, body))
 */
class SmsActionSink(
    private val bankHp: (amount: Double, from: String, note: String) -> Unit,
    private val reward: (missionId: String, location: String, amount: Double) -> Unit,
    private val log: (String) -> Unit = {},
) {

    /** Apply a decision to the injected side-effects. Returns true if an action ran. */
    fun apply(decision: SmsGateway.Decision): Boolean = when (decision) {
        is SmsGateway.Decision.BankHp -> {
            log.invoke("BankHp ${decision.amount} from ${decision.from}")
            bankHp(decision.amount, decision.from, decision.note)
            true
        }

        is SmsGateway.Decision.Reward -> {
            log.invoke("Reward ${decision.amount} for ${decision.missionId}")
            reward(decision.missionId, decision.location, decision.amount)
            true
        }

        SmsGateway.Decision.Ignore -> false
    }

    /** One call: parse + decide (pure gateway) then apply. Returns true on action. */
    fun process(sms: SmsGateway.Sms): Boolean {
        val decision = SmsGateway().handle(sms) // default no-op callbacks
        return apply(decision)
    }
}
