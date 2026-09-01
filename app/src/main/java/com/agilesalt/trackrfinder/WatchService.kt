package com.agilesalt.trackrfinder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a filtered BLE scan alive so the phone notices when the tracker stops
 * answering, records where we were the last time it did, and says so.
 *
 * This is the part a Web Bluetooth page cannot do.
 */
@SuppressLint("MissingPermission")
class WatchService : Service() {

    private lateinit var scanner: TrackrScanner
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var address: String? = null
    private var wasInRange = true

    /**
     * Guards against alerting for a tag that was already out of range when the
     * watch was armed: without this, arming while away fires immediately.
     */
    private var seenSinceStart = false

    /** Whether this service instance holds the location capability. */
    private var locationAllowed = false

    /**
     * Evaluation is suspended until this time. With the radio off we cannot
     * tell whether the tag is nearby, and silence must not be read as
     * departure -- turning Bluetooth off previously produced a "left behind"
     * alert for a tag on the table. The same grace applies after the radio
     * returns, so re-acquiring does not race the window.
     */
    private var suspendedUntil = 0L

    /**
     * When the tag first went quiet past the window. Silence alone is not
     * proof: at the edge of range gaps of over a minute occur on a tag sitting
     * in the same room (measured: median gap 2.2s, but a maximum of 78s at
     * -85 dBm). So before alerting we escalate to the most sensitive scan mode
     * and require continued silence, rather than simply widening the window
     * and making every genuine alert later.
     */
    private var confirmingSince = 0L

    /** Nickname if the user set one, else whatever we stored at watch time. */
    private val label: String
        get() = address?.let { prefs.nickname(it) } ?: prefs.watchedName ?: "Tracker"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scanner = TrackrScanner(this)
        prefs = Prefs(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            address = prefs.watchedAddress
            notifyLeftBehind()
            return START_NOT_STICKY
        }
        val next = prefs.watchedAddress
        if (next == null || !prefs.watchEnabled) {
            // Belt and braces: if we were started via startForegroundService we
            // owe the platform a startForeground call even on the way out, or
            // it kills the process for breaching the contract.
            enterForeground(false)
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (next != address) {
            // A different tag: nothing learned about the previous one carries
            // over. Without this, switching from a tag that was in range to one
            // that is not would fire an alert immediately, because the service
            // still believed it had been in contact.
            wasInRange = true
            seenSinceStart = false
            confirmingSince = 0L
            notificationManager.cancel(NOTIF_ALERT)
        }
        address = next

        // "location" is a while-in-use foreground service type, which Android
        // forbids starting from a background context such as BOOT_COMPLETED --
        // and it refuses the whole start, not just that capability. So at boot
        // we claim only connectedDevice, which is enough to keep scanning, and
        // upgrade to include location when the app is next opened.
        locationAllowed = intent?.getBooleanExtra(EXTRA_WITH_LOCATION, false) ?: false
        if (!enterForeground(locationAllowed)) {
            stopSelf()
            return START_NOT_STICKY
        }
        scanner.start(address = address, scanMode = TrackrScanner.Mode.BALANCED)
        // onStartCommand runs again on re-arm and on system restart; without
        // this each call would stack another tick loop.
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            val addr = address
            if (addr != null && !scanner.bluetoothEnabled) {
                // Cannot observe; therefore cannot conclude.
                confirmingSince = 0L
                suspendedUntil = System.currentTimeMillis() + RADIO_GRACE_MS
                notificationManager.notify(
                    NOTIF_ONGOING,
                    ongoingNotification("$label — waiting for Bluetooth", null),
                )
            } else if (addr != null && System.currentTimeMillis() < suspendedUntil) {
                notificationManager.notify(
                    NOTIF_ONGOING,
                    ongoingNotification("$label — reconnecting", null),
                )
            } else if (addr != null) {
                val sighting = scanner.sightings.value[addr]
                val now = System.currentTimeMillis()

                if (sighting != null && sighting.ageMillis < IN_RANGE_WINDOW_MS) {
                    prefs.lastSeenAt = sighting.seenAt
                    recordLocation()
                    seenSinceStart = true
                    if (confirmingSince > 0L) {
                        // Heard it again during confirmation: a weak link, not
                        // a departure. Drop back to the cheaper scan mode.
                        confirmingSince = 0L
                        scanner.start(addr, TrackrScanner.Mode.BALANCED)
                    }
                    if (!wasInRange) {
                        wasInRange = true
                        notificationManager.cancel(NOTIF_ALERT)
                    }
                    notificationManager.notify(
                        NOTIF_ONGOING,
                        ongoingNotification(
                            "$label is nearby",
                            "%.0f m away  ·  %d dBm".format(sighting.approxMeters, sighting.rssi),
                        ),
                    )
                } else {
                    if (wasInRange && seenSinceStart) {
                        when {
                            // First silence past the window: listen harder
                            // before concluding anything.
                            confirmingSince == 0L -> {
                                confirmingSince = now
                                scanner.start(addr, TrackrScanner.Mode.LOW_LATENCY)
                            }
                            // Still nothing at maximum sensitivity: it is gone.
                            now - confirmingSince >= CONFIRM_MS -> {
                                wasInRange = false
                                confirmingSince = 0L
                                scanner.start(addr, TrackrScanner.Mode.BALANCED)
                                notifyLeftBehind()
                            }
                        }
                    }
                    notificationManager.notify(
                        NOTIF_ONGOING,
                        ongoingNotification("$label out of range", lastSeenLine()),
                    )
                }
                scanner.expireOlderThan(EXPIRE_MS)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun recordLocation() {
        // Without the location service type the platform will not hand us a fix
        // anyway; skip rather than fail noisily every tick.
        if (!locationAllowed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val best = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull() ?: return
        prefs.lastLat = best.latitude
        prefs.lastLon = best.longitude
    }

    private fun lastSeenLine(): String {
        val seen = prefs.lastSeenAt
        if (seen == 0L) return "Not seen yet"
        val mins = (System.currentTimeMillis() - seen) / 60_000
        val when_ = if (mins < 1) "just now" else "${mins} min ago"
        return if (prefs.hasLocation) {
            "Last seen %s at %.5f, %.5f".format(when_, prefs.lastLat, prefs.lastLon)
        } else "Last seen $when_"
    }

    private fun notifyLeftBehind() {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("$label left behind")
            .setContentText(lastSeenLine())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lastSeenLine()))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)  // pre-Oreo path
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager.notify(NOTIF_ALERT, n)
    }

    private fun ongoingNotification(title: String, body: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(
                0, "Stop",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, WatchService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private val notificationManager: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun stopForegroundAndSelf() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** @return false when the platform refuses the foreground start entirely. */
    private fun enterForeground(withLocation: Boolean): Boolean = try {
        val notification = ongoingNotification("Watching $label", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (withLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ONGOING, notification, types)
        } else {
            startForeground(NOTIF_ONGOING, notification)
        }
        true
    } catch (e: Exception) {
        Log.w("WatchService", "foreground start refused (withLocation=$withLocation)", e)
        false
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Watching", NotificationManager.IMPORTANCE_LOW)
        )
        // Channel settings are fixed at creation; importance alone does not
        // grant sound. Everything audible has to be spelled out here, not on
        // the notification, where setPriority is ignored on Android 8+.
        val alert = NotificationChannel(
            CHANNEL_ALERT, "Left behind", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires when a watched tag stops responding"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            enableLights(true)
            // Alarm usage so it is audible with the ringer down: the whole
            // point is to catch you walking away from your keys.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build(),
            )
            // Show the tag name and coordinate on the lock screen, which is
            // where this notification is actually read.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(alert)

        // The v1 channel is silent and unfixable; remove it so it stops
        // appearing under the app's notification settings.
        runCatching { notificationManager.deleteNotificationChannel("left_behind") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scanner.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.agilesalt.trackrfinder.STOP"
        const val ACTION_TEST = "com.agilesalt.trackrfinder.TEST"
        private const val CHANNEL_ONGOING = "watching"
        // Bumped: channel settings are immutable once created, so the
        // original silent channel had to be replaced outright.
        private const val CHANNEL_ALERT = "left_behind_v2"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_ALERT = 2
        private const val TICK_MS = 5_000L

        /** Settling time after the radio returns before absence means anything. */
        private const val RADIO_GRACE_MS = 30_000L

        /**
         * How long without hearing a tag before it counts as gone. Shared with
         * the UI so both agree on what "out of range" means -- they previously
         * used different sources and could disagree indefinitely.
         */
        const val IN_RANGE_WINDOW_MS = 45_000L

        /** Extra silence required, at maximum scan sensitivity, before alerting. */
        const val CONFIRM_MS = 20_000L

        /** What the UI should treat as out of range, so it agrees with the alert. */
        const val OUT_OF_RANGE_MS = IN_RANGE_WINDOW_MS + CONFIRM_MS
        private const val EXPIRE_MS = 300_000L

        const val EXTRA_WITH_LOCATION = "with_location"

        /**
         * @param withLocation only from a visible app. Requesting the location
         * service type from the background makes the platform refuse the start.
         */
        fun start(context: Context, withLocation: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WatchService::class.java)
                    .putExtra(EXTRA_WITH_LOCATION, withLocation),
            )
        }

        /** Fire the left-behind alert immediately, to check it is noticeable. */
        fun testAlert(context: Context) {
            context.startService(
                Intent(context, WatchService::class.java).setAction(ACTION_TEST)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WatchService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
