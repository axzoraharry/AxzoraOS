package com.axzora.os.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Inbound SMS gateway: receives a real SMS and routes it through [SmsGateway].
 *
 * NOTE: this is Android-framework wiring and must be built with the full app on
 * a real machine (it can't be compiled in the unit-test sandbox). It is the
 * "inbound receiver" piece of the Communication Layer. Production wiring should
 * use goAsync() and a repository/coroutine scope to do the wallet/mint work off
 * the main thread — this scaffold logs the decision.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val gateway = SmsGateway()
        for (m in messages) {
            val from = m.originatingAddress ?: ""
            val body = m.displayMessageBody ?: m.messageBody ?: ""
            val decision = gateway.handle(SmsGateway.Sms(from, body))
            // TODO: on Decision.BankHp -> wallet.credit(amount, from, note, Rail.SMS);
            //       on Decision.Reward -> AdminTools.mint(missionId, reward) + impact score.
            Log.i("AxzoraSms", "from=$from decision=$decision")
        }
    }
}
