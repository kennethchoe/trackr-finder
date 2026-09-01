package com.agilesalt.trackrfinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Without this the leave-behind watch silently dies on reboot: no notification,
 * nothing scanning, and the app looks perfectly normal next time you open it.
 * An alarm you trust that is not actually running is worse than no alarm.
 *
 * A service started from boot is a background start, so the system may withhold
 * location. The alert itself is unaffected -- only the recorded coordinate is,
 * and MainActivity re-arms from the foreground to restore it.
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
            // Logged on success too: without this there is no way to tell a
            // working boot restart from one that never ran, since the service
            // also gets re-armed whenever the app is opened.
            Log.i(TAG, "boot: watch restart requested")
        } catch (e: Exception) {
            // Android 14+ can refuse a foreground service start from boot.
            Log.w(TAG, "boot: could not restart watch", e)
        }
    }
}
