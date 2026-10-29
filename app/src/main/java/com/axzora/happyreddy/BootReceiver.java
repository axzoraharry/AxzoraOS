package com.axzora.happyreddy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Brings Mr Happy's ear back after a reboot (and after the app is updated),
 * so "always listening" really means always — not "until the next restart".
 */
public class BootReceiver extends BroadcastReceiver {

    static final String PREFS = "mrhappy_ear";
    static final String KEY_ALWAYS_ON = "always_on";

    public static boolean alwaysOn(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Default ON: the owner asked for an always-listening assistant ("Mr
        // Happy, keep listening"). The background mic is a powerful thing, so
        // this is the user's deliberate choice — and it can be toggled off in
        // Mr Happy's settings.
        return p.getBoolean(KEY_ALWAYS_ON, true);
    }

    public static void setAlwaysOn(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALWAYS_ON, on).apply();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            if (alwaysOn(ctx)) MrHappyService.start(ctx);
            // OpenRoom lives in Termux and dies with a reboot; bring it back
            // a little after boot so Termux itself has had time to come up.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> com.axzora.os.openroom.OpenRoom.INSTANCE.start(ctx), 25_000);
        }
    }
}
