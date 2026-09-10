package com.agilesalt.trackrfinder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Separate from the phone notification watch: either mode can run on its own. */
class TrackerAlertService : Service() {
    private lateinit var prefs: Prefs
    private var connection: TrackerAlertConnection? = null
    private var finished = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tracker alarm", NotificationManager.IMPORTANCE_LOW))
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) prefs.trackerAlertEnabled = false
        val addr = prefs.trackerAlertAddress
        try {
            val n = notification(_state.value.takeIf { it.address == addr }?.message ?: "Connecting to tracker…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, n)
        } catch (e: Exception) {
            _state.value = TrackerAlertState(addr, TrackerAlertPhase.ERROR, enabled = prefs.trackerAlertEnabled,
                message = "Open the app to resume the tracker alarm: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (addr == null || !prefs.trackerAlertNeedsService) {
            prefs.clearTrackerAlert()
            _state.value = TrackerAlertState()
            finish()
            return START_NOT_STICKY
        }
        finished = false
        if (connection?.address != addr) {
            connection?.close()
            connection = null
        }
        if (connection == null) {
            connection = TrackerAlertConnection(this, addr, prefs, onState = {
                _state.value = it
                if (it.phase != TrackerAlertPhase.OFF) manager.notify(NOTIFICATION, notification(it.message))
            }, onFinished = { finish() })
        }
        connection?.settingsChanged()
        return START_STICKY
    }

    private fun finish() {
        finished = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connection?.close()
        connection = null
        if (instance === this) instance = null
        if (!finished && prefs.trackerAlertNeedsService) {
            _state.value = TrackerAlertState(prefs.trackerAlertAddress, TrackerAlertPhase.ERROR,
                enabled = prefs.trackerAlertEnabled,
                message = if (prefs.trackerAlertEnabled) "Connection stopped. Open the app to reconnect."
                    else "Off is not confirmed. Bring the tracker nearby and tap Retry.")
        }
        super.onDestroy()
    }

    private val manager get() = getSystemService(NotificationManager::class.java)

    private fun notification(body: String): Notification {
        val label = prefs.trackerAlertAddress?.let { prefs.nickname(it) } ?: prefs.trackerAlertName ?: "Tracker"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Tracker alarm · $label")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 10,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Turn off tracker alarm", PendingIntent.getService(this, 11,
                Intent(this, TrackerAlertService::class.java).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        const val ACTION_OFF = "com.agilesalt.trackrfinder.TRACKER_ALARM_OFF"
        private const val CHANNEL = "tracker_alarm"
        private const val NOTIFICATION = 10
        private var instance: TrackerAlertService? = null
        private val _state = MutableStateFlow(TrackerAlertState())
        internal val state = _state.asStateFlow()

        internal fun setEnabled(context: Context, address: String, name: String, enabled: Boolean) {
            val prefs = Prefs(context)
            require(prefs.trackerAlertAddress == null || prefs.trackerAlertAddress == address) {
                "Turn off the other tracker’s alarm first."
            }
            prefs.trackerAlertAddress = address
            prefs.trackerAlertName = name
            prefs.trackerAlertEnabled = enabled
            prefs.rememberDevice(address)
            start(context)
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TrackerAlertService::class.java))
            } catch (e: Exception) {
                val prefs = Prefs(context)
                _state.value = TrackerAlertState(prefs.trackerAlertAddress, TrackerAlertPhase.ERROR,
                    enabled = prefs.trackerAlertEnabled, message = "Could not start tracker alarm: ${e.message}")
                throw e
            }
        }

        internal fun ring(address: String, stop: Boolean, result: (String?) -> Unit) {
            val current = instance?.connection
            if (current == null || current.address != address) result("Tracker alarm is not connected.")
            else current.ring(stop, result)
        }

        internal fun isConnected(address: String?): Boolean =
            address != null && state.value.address == address && state.value.connected
    }
}
