package dev.kchoe.trackrfinder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
            stopSelf()
            return START_NOT_STICKY
        }
        address = prefs.watchedAddress
        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ONGOING, ongoingNotification("Watching $label", null))
        scanner.start(address = address, lowPower = true)
        // onStartCommand runs again on re-arm and on system restart; without
        // this each call would stack another tick loop.
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            val addr = address
            if (addr != null) {
                val sighting = scanner.sightings.value[addr]
                val now = System.currentTimeMillis()

                if (sighting != null && sighting.ageMillis < IN_RANGE_WINDOW_MS) {
                    prefs.lastSeenAt = sighting.seenAt
                    recordLocation()
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
                    if (wasInRange && prefs.lastSeenAt > 0) {
                        wasInRange = false
                        notifyLeftBehind()
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

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Watching", NotificationManager.IMPORTANCE_LOW)
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Left behind", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scanner.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.kchoe.trackrfinder.STOP"
        private const val CHANNEL_ONGOING = "watching"
        private const val CHANNEL_ALERT = "left_behind"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_ALERT = 2
        private const val TICK_MS = 10_000L
        private const val IN_RANGE_WINDOW_MS = 90_000L
        private const val EXPIRE_MS = 300_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WatchService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
