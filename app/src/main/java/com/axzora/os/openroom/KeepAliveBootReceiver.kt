package com.axzora.os.openroom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the keep-alive service after reboot or an app update. */
class KeepAliveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            KeepAliveService.start(context)
        }
    }
}
