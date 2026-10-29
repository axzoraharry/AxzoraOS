package com.axzora.happyreddy

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Offline UPI over USSD — a guided, scripted flow on top of the existing
 * [HappyReddyA11y] accessibility service (ported from OffPay's idea, but with a
 * deliberate security boundary).
 *
 * The flow dials a *99# code, then reads the carrier's USSD dialog and types
 * the VPA + amount, but **STOPS before the PIN**. The user types their UPI PIN
 * themselves in the carrier's own dialog — this app never stores, sees, or
 * enters a PIN. That's both safer and more reliable, because PIN prompts vary
 * wildly between carriers and OEMs.
 *
 * Honest scope: this is a *best-effort* guide. USSD dialogs differ by carrier,
 * so it reads the screen to decide the next step rather than blindly typing.
 */
object UssdScript {

    private const val SEND = "*99*1*3#"
    private const val BALANCE = "*99*3#"

    /** Dial a USSD code, URL-encoding the '#'. Returns a status string. */
    fun dial(context: Context, code: String): String {
        val encoded = code.replace("#", Uri.encode("#"))
        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$encoded"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Dialled $code"
        } catch (e: SecurityException) {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$encoded"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "Opened the dialer for $code — tap the call button, then I'll guide you."
        }
    }

    /**
     * Run the guided send-money flow: dial *99*1*3#, then read the screen and
     * type the VPA + amount when prompted, stopping before the PIN.
     *
     * Blocking (caller runs it off the UI thread). Returns a status message.
     */
    fun sendMoneyFlow(vpa: String, amount: String): String {
        val a11y = HappyReddyA11y.get()
        if (a11y == null) {
            return "Screen control isn't on. Open Accessibility settings and turn on " +
                "'Mr Happy Control', then try again."
        }
        Thread.sleep(2500)

        var screen = a11y.readScreen().lowercase()
        if (screen.contains("upi") || screen.contains("vpa") || screen.contains("receiver") ||
            screen.contains("beneficiary") || screen.contains("send to") || screen.contains("enter")) {
            if (vpa.isNotBlank()) {
                a11y.typeText(vpa)
                Thread.sleep(600)
                a11y.tapText("send")
                Thread.sleep(1500)
            }
        }

        screen = a11y.readScreen().lowercase()
        if (screen.contains("amount")) {
            if (amount.isNotBlank()) {
                a11y.typeText(amount)
                Thread.sleep(600)
                a11y.tapText("send")
                Thread.sleep(1500)
            }
        }

        return "I've dialled the UPI menu and filled what I safely can. " +
            "Please enter your UPI PIN in the dialog to finish — I never see or enter your PIN."
    }

    /** Check-balance flow: dial *99*3#, the carrier asks only for the PIN. */
    fun balanceFlow(): String {
        val a11y = HappyReddyA11y.get()
        if (a11y == null) {
            return "Screen control isn't on. Open Accessibility settings and turn on " +
                "'Mr Happy Control', then try again."
        }
        Thread.sleep(2500)
        return "I've dialled *99*3# for your balance. Enter your UPI PIN in the dialog to see it."
    }

    // Exposed for the dispatcher so it doesn't need the raw codes.
    fun sendCode(): String = SEND
    fun balanceCode(): String = BALANCE
}
