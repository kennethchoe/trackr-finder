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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps a filtered BLE scan alive so the phone notices when a tag stops
 * answering, and records where it was last heard.
 */
@SuppressLint("MissingPermission")
class WatchService : Service() {

    private lateinit var scanner: TrackrScanner
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var address: String? = null
    private var wasInRange = true

    /** No alert for a tag never heard since the watch was armed. */
    private var seenSinceStart = false

    /** Whether this service instance holds the location capability. */
    private var locationAllowed = false

    /**
     * Evaluation is suspended until this time: with the radio off, or just
     * back, silence says nothing about where the tag is.
     */
    private var suspendedUntil = 0L

    /**
     * When the tag went quiet past the window. At the edge of range a tag in
     * the same room can go unheard for over a minute, so alerting waits for
     * continued silence at the most sensitive scan mode.
     */
    private var confirmingSince = 0L

    /** When contact resumed after an alert; a return has to be sustained. */
    private var backSince = 0L

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
            notifyTest()
            if (address == null) stopSelf()
            return START_NOT_STICKY
        }
        val next = prefs.watchedAddress
        if (next == null || !prefs.watchEnabled) {
            // A startForegroundService start owes a startForeground call even
            // on the way out.
            enterForeground(false)
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (next != address) {
            // A different tag: nothing learned about the last one carries over.
            wasInRange = true
            seenSinceStart = false
            confirmingSince = 0L
            backSince = 0L
            _outOfRange.value = null
            notificationManager.cancel(NOTIF_ALERT)
        }
        address = next

        // "location" is a while-in-use type, which cannot be claimed from a
        // background start; connectedDevice alone is enough to keep scanning.
        locationAllowed = intent?.getBooleanExtra(EXTRA_WITH_LOCATION, false) ?: false
        if (!enterForeground(locationAllowed)) {
            stopSelf()
            return START_NOT_STICKY
        }
        scanner.start(address = address, scanMode = TrackrScanner.Mode.BALANCED)
        // onStartCommand runs again on re-arm and restart; one loop only.
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
                backSince = 0L
                suspendedUntil = System.currentTimeMillis() + RADIO_GRACE_MS
                _outOfRange.value = null
                notificationManager.notify(
                    NOTIF_ONGOING,
                    ongoingNotification("$label — waiting for Bluetooth", null),
                )
            } else if (addr != null && System.currentTimeMillis() < suspendedUntil) {
                _outOfRange.value = null
                notificationManager.notify(
                    NOTIF_ONGOING,
                    ongoingNotification("$label — reconnecting", null),
                )
            } else if (addr != null) {
                val now = System.currentTimeMillis()
                val sighting = scanner.sightings.value[addr]
                // The open screen runs its own scan; either radio hearing the
                // tag counts, so the alert cannot contradict what is on screen.
                sighting?.let { if (it.seenAt > prefs.lastSeenAt) prefs.lastSeenAt = it.seenAt }
                val lastHeard = prefs.lastSeenAt
                val silentFor = if (lastHeard == 0L) Long.MAX_VALUE else now - lastHeard

                if (silentFor < IN_RANGE_WINDOW_MS) {
                    recordLocation()
                    seenSinceStart = true
                    if (confirmingSince > 0L) {
                        // Heard again during confirmation: a weak link, not a departure.
                        confirmingSince = 0L
                        scanner.start(addr, TrackrScanner.Mode.BALANCED)
                    }
                }

                if (!wasInRange) {
                    // A stray packet at the edge of range is not a return. Taken
                    // as one it re-arms the watch, which alerts again a minute
                    // later, and again, for a tag that never actually came back.
                    // Contact shows on screen straight away; only re-arming waits.
                    if (silentFor < RETURN_FRESH_MS) {
                        if (backSince == 0L) backSince = now
                        if (now - backSince >= RETURN_MS) {
                            wasInRange = true
                            backSince = 0L
                            notifyBack()
                        }
                    } else {
                        backSince = 0L
                    }
                } else if (silentFor >= IN_RANGE_WINDOW_MS && seenSinceStart) {
                    when {
                        // Listen harder before concluding anything.
                        confirmingSince == 0L -> {
                            confirmingSince = now
                            scanner.start(addr, TrackrScanner.Mode.LOW_LATENCY)
                        }
                        // Still nothing at maximum sensitivity.
                        now - confirmingSince >= CONFIRM_MS -> {
                            wasInRange = false
                            confirmingSince = 0L
                            scanner.start(addr, TrackrScanner.Mode.BALANCED)
                            notifyLeftBehind()
                        }
                    }
                }

                val gone = !wasInRange && backSince == 0L
                _outOfRange.value = gone
                val live = sighting?.takeIf { it.ageMillis < IN_RANGE_WINDOW_MS }
                notificationManager.notify(
                    NOTIF_ONGOING,
                    when {
                        !seenSinceStart -> ongoingNotification("Watching $label", "Not seen yet")
                        gone -> ongoingNotification("$label out of range", lastSeenLine())
                        live != null -> ongoingNotification(
                            "$label is nearby",
                            "%.0f m away  ·  %d dBm".format(live.approxMeters, live.rssi),
                        )
                        else -> ongoingNotification("$label is nearby", lastSeenLine())
                    },
                )
                scanner.expireOlderThan(EXPIRE_MS)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun recordLocation() {
        // Without the location service type no fix is forthcoming.
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

    private fun alertNotification(body: String) = NotificationCompat.Builder(this, CHANNEL_ALERT)
        .setContentTitle("$label left behind")
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setDefaults(NotificationCompat.DEFAULT_ALL)  // pre-Oreo path
        .setAutoCancel(true)
        .setContentIntent(openAppIntent())

    private fun notifyLeftBehind() =
        notificationManager.notify(NOTIF_ALERT, alertNotification(lastSeenLine()).build())

    private fun notifyTest() = notificationManager.notify(
        NOTIF_TEST,
        alertNotification(
            "This is a test. You will get an alert like this when you leave "
                + "this tag behind, even when the phone is locked."
        ).build(),
    )

    /**
     * Replaces the alert rather than cancelling it, so the shade still records
     * that the tag was left behind and says how that ended.
     */
    private fun notifyBack() {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle("$label is back")
            .setContentText("Was out of range. Back in range now.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)  // an update, not a new event
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
        _outOfRange.value = null
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
        // Channel settings are fixed at creation, and importance alone does
        // not grant sound, so everything audible is spelled out here.
        val alert = NotificationChannel(
            CHANNEL_ALERT, "Left behind", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires when a watched tag stops responding"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            enableLights(true)
            // Alarm usage so it is audible with the ringer down.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build(),
            )
            // The lock screen is where this is read.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(alert)

        // Superseded channel; removing it keeps notification settings tidy.
        runCatching { notificationManager.deleteNotificationChannel("left_behind") }
    }

    override fun onDestroy() {
        _outOfRange.value = null
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
        // Its own id, so a test never overwrites or clears a real alert.
        private const val NOTIF_TEST = 3
        private const val TICK_MS = 5_000L

        private val _outOfRange = MutableStateFlow<Boolean?>(null)

        /**
         * The running watch's verdict, so the screen and the alert say the same
         * thing. Null while nothing is being judged: no watch, or the radio is
         * off or has only just come back.
         */
        val outOfRange: StateFlow<Boolean?> = _outOfRange.asStateFlow()

        /** Settling time after the radio returns before absence means anything. */
        private const val RADIO_GRACE_MS = 30_000L

        /** Silence after which a tag counts as gone. Shared with the UI. */
        const val IN_RANGE_WINDOW_MS = 45_000L

        /** Extra silence required, at maximum scan sensitivity, before alerting. */
        const val CONFIRM_MS = 20_000L

        /** Out of range, once the watch is not running to say so itself. */
        const val OUT_OF_RANGE_MS = IN_RANGE_WINDOW_MS + CONFIRM_MS

        /** How recently the tag must be heard to count towards a return. */
        private const val RETURN_FRESH_MS = 15_000L

        /** Contact has to hold this long before an alert is called off. */
        private const val RETURN_MS = 20_000L
        private const val EXPIRE_MS = 300_000L

        const val EXTRA_WITH_LOCATION = "with_location"

        /** @param withLocation only from a visible app; a background start is refused. */
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
