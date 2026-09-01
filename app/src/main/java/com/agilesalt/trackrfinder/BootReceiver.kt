package com.agilesalt.trackrfinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores the leave-behind watch after a reboot.
 *
 * This is a background start, so the system may withhold location; the alert
 * is unaffected and MainActivity re-arms from the foreground to restore the
 * coordinate.
 */
class BootReceiver : BroadcastReceiver() {

    private companion object { const val TAG = "BootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val prefs = Prefs(context)
        if (prefs.watchedAddress == null || !prefs.watchEnabled) {
            Log.i(TAG, "boot: no watch armed, nothing to restart")
            return
        }

        try {
            WatchService.start(context)
            // Logged on success so a boot restart is distinguishable from the
            // re-arm that happens whenever the app is opened.
            Log.i(TAG, "boot: watch restart requested")
        } catch (e: Exception) {
            // Android 14+ can refuse a foreground service start from boot.
            Log.w(TAG, "boot: could not restart watch", e)
        }
    }
}
