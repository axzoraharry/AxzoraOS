package com.axzora.os.assistant

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The receiver Android requires before an app may lock the screen.
 *
 * It holds no policy of its own — its existence is simply what lets
 * [DeviceControl.lock] work once the user grants the right.
 */
class AxzoraAdmin : DeviceAdminReceiver()

/**
 * Locking and unlocking the phone by voice.
 *
 * Locking is a real capability, granted deliberately: Android only allows it
 * for an app the user has made a device admin, which is a screen they must
 * accept themselves.
 *
 * Unlocking is deliberately *not* possible, and no app can do it. If an
 * assistant could unlock a phone by being asked, then so could anyone
 * holding it — the lock would mean nothing. The most that can be done is to
 * raise the unlock prompt so the user's own PIN, fingerprint or face finishes
 * the job, which is what [requestUnlock] does.
 */
class DeviceControl(private val context: Context) {

    private val dpm: DevicePolicyManager
        get() = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val admin = ComponentName(context, AxzoraAdmin::class.java)

    val canLock: Boolean get() = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)

    val isLocked: Boolean
        get() = runCatching {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .isKeyguardLocked
        }.getOrDefault(false)

    /** @return true if the screen was locked. */
    fun lock(): Boolean = runCatching {
        if (!canLock) return false
        dpm.lockNow()
        true
    }.getOrDefault(false)

    /** Opens the screen where the user grants the lock permission. */
    fun requestLockPermission(): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets Axzora lock the screen when you ask it to. " +
                    "It grants nothing else — Axzora cannot wipe, restrict or " +
                    "watch the phone.",
            )

    fun removeLockPermission() {
        runCatching { dpm.removeActiveAdmin(admin) }
    }
}
