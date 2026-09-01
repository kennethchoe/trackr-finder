package dev.kchoe.trackrfinder

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
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (Prefs(context).watchedAddress == null) return

        try {
            WatchService.start(context)
        } catch (e: Exception) {
            // Android 14+ can refuse a foreground service start from boot.
            // Nothing useful to do here; opening the app re-arms it.
            Log.w("BootReceiver", "could not restart watch at boot", e)
        }
    }
}
