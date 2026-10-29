package com.axzora.os.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Android transport for [SmsGateway.send]. Provides the `sendText` lambda the
 * gateway needs, backed by `SmsManager.sendTextMessage`. The gateway stays
 * dependency-free; the app passes `SmsAndroidSender.sendText(context)` to it.
 *
 * NOTE: framework wiring — must be built on a real machine.
 */
object SmsAndroidSender {

    fun sendText(context: Context): (String, String) -> Boolean = { number, body ->
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted || number.isBlank() || body.isBlank()) {
            false
        } else {
            runCatching { SmsManager.getDefault().sendTextMessage(number, null, body, null, null) }
                .isSuccess
        }
    }
}
