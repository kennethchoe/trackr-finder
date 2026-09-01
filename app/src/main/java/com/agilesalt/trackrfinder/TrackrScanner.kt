package com.agilesalt.trackrfinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.exp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps the BLE scanner and keeps a live map of currently-visible TrackR devices.
 *
 * Two modes, deliberately:
 *  - Discovery (filter == null): an unfiltered scan, used only while the user is
 *    looking at the app. Android throttles unfiltered scans and returns nothing
 *    at all with the screen off, so this is a foreground-only mode.
 *  - Watch (filter == a MAC): a ScanFilter pinned to one address. Filtered scans
 *    are the only kind that keep delivering results with the screen off, which is
 *    what makes the background service work.
 */
@SuppressLint("MissingPermission")
class TrackrScanner(context: Context) {

    private val appContext = context.applicationContext

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _sightings = MutableStateFlow<Map<String, Sighting>>(emptyMap())
    val sightings: StateFlow<Map<String, Sighting>> = _sightings.asStateFlow()

    private var scanning = false
    private var activeAddress: String? = null
    private var activeMode: Mode? = null

    /**
     * Android permits five scan starts per 30 seconds, then silently refuses
     * registration -- the rejection happens client-side, so ScanCallback never
     * even sees it. Stopping on every app switch burned through that budget and
     * left the scanner dead with no error anywhere except logcat. So a stop is
     * deferred: a quick switch away and back cancels it and keeps the existing
     * registration rather than spending another start.
     */
    private val handler = Handler(Looper.getMainLooper())
    private val deferredStop = Runnable { stopNow() }

    // What the caller wants, as opposed to what is currently registered. The
    // two diverge when the Bluetooth stack drops our registration underneath us.
    private var wantScanning = false
    private var wantAddress: String? = null
    private var wantMode: Mode = Mode.LOW_LATENCY

    /**
     * Turning Bluetooth off silently discards every scan registration. Nothing
     * is delivered to the app, so `scanning` stayed true, results stopped, and
     * the throttle heuristic latched on forever -- the scan never came back
     * when Bluetooth did.
     */
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    scanning = false
                    activeAddress = null
                    activeMode = null
                    startedAt = 0L
                    lastResultAt = 0L
                    // Old sightings say nothing about the present.
                    _sightings.value = emptyMap()
                }
                BluetoothAdapter.STATE_ON -> {
                    if (wantScanning) start(wantAddress, wantMode)
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            adapterStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Detach from the adapter broadcast. Safe to call more than once. */
    fun release() {
        runCatching { appContext.unregisterReceiver(adapterStateReceiver) }
        stopNow()
    }

    /** Set when a scan is registered but no advertisement has arrived since. */
    private var lastResultAt = 0L
    private var startedAt = 0L

    /** How long the current scan has been registered, 0 when not scanning. */
    val scanActiveMillis: Long
        get() = if (scanning && startedAt > 0) System.currentTimeMillis() - startedAt else 0L

    /** Timestamps of our own startScan calls, for the throttle rule below. */
    private val recentStarts = ArrayDeque<Long>()

    /**
     * Android refuses the sixth scan start within thirty seconds, silently. The
     * state cannot be queried, but it can be counted: these are our own calls,
     * so this is evidence rather than inference.
     *
     * The previous version guessed from silence, which also fired whenever
     * nothing happened to be advertising -- reporting a platform fault when the
     * room was merely empty.
     */
    val looksThrottled: Boolean
        get() {
            val cutoff = System.currentTimeMillis() - THROTTLE_WINDOW_MS
            return recentStarts.count { it >= cutoff } >= THROTTLE_MAX_STARTS
        }

    /**
     * Display order, assigned once per address and never reset. Survives
     * expiry: a device that advertises every 30s would otherwise be dropped by
     * the 20s expiry and return as "new", jumping to the end of the list on
     * every cycle. Not cleared, so ordering is stable for the whole session.
     */
    private val firstSeenOrder = mutableMapOf<String, Long>()

    /**
     * When true, every advertisement is listed, not just probable tags. Off by
     * default because a BLE scan in a populated area is mostly headphones,
     * televisions and other people's phones.
     */
    var showAll: Boolean = false
        set(value) {
            field = value
            if (!value) {
                // Drop entries that were only there because of this toggle.
                _sightings.value = _sightings.value
                    .filterValues { it.matchReason != MatchReason.SHOW_ALL }
            }
        }

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::record)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed, error $errorCode")
            scanning = false
        }
    }

    private fun record(result: ScanResult) {
        lastResultAt = System.currentTimeMillis()
        val record = result.scanRecord
        // device.name requires BLUETOOTH_CONNECT on some builds and can be null
        // before a connection; the advertised name in the scan record does not.
        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()

        // The authoritative signal: the device says it speaks Immediate Alert.
        val advertisesAlert =
            record?.serviceUuids?.any { it.uuid == Trackr.IMMEDIATE_ALERT } == true

        val reason = when {
            advertisesAlert -> MatchReason.ALERT_SERVICE
            Trackr.isKnownTagName(name) -> MatchReason.KNOWN_NAME
            showAll -> MatchReason.SHOW_ALL
            else -> return
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "adv ${result.device.address} name=${name ?: "<none>"} " +
                "rssi=${result.rssi} match=$reason")
        }

        val address = result.device.address
        val prev = _sightings.value[address]
        // Weight each reading by how long it has been since the last one, so a
        // device advertising twice a second and one advertising every three
        // seconds both settle at the same rate in wall-clock terms.
        val smoothed = if (prev == null) result.rssi.toFloat() else {
            val elapsed = (System.currentTimeMillis() - prev.seenAt).coerceAtLeast(1L)
            val alpha = (1.0 - exp(-elapsed / Sighting.TIME_CONSTANT_MS))
                .toFloat().coerceIn(0.02f, 1f)
            prev.smoothedRssi + alpha * (result.rssi - prev.smoothedRssi)
        }

        _sightings.value = _sightings.value + (address to Sighting(
            address = address,
            name = displayName(name),
            rssi = result.rssi,
            matchReason = reason,
            smoothedRssi = smoothed,
            firstSeen = firstSeenOrder.getOrPut(address) { System.currentTimeMillis() },
        ))
    }

    enum class Mode { LOW_LATENCY, BALANCED, LOW_POWER }

    /** @param address pin the scan to one device (required for background scanning). */
    fun start(address: String? = null, scanMode: Mode = Mode.LOW_LATENCY): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false

        handler.removeCallbacks(deferredStop)
        wantScanning = true
        wantAddress = address
        wantMode = scanMode

        // Already registered with these exact parameters: reuse it rather than
        // spending another start against the throttle budget.
        if (scanning && activeAddress == address && activeMode == scanMode) return true

        if (scanning) stopNow()

        val filters = address?.let {
            listOf(ScanFilter.Builder().setDeviceAddress(it).build())
        } ?: emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(
                when (scanMode) {
                    // LOW_POWER duty-cycles so sparsely that with the screen off
                    // a tag could go unheard for minutes, delaying the alert.
                    Mode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                    Mode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                    Mode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                }
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        return try {
            scanner.startScan(filters, settings, callback)
            val startedNow = System.currentTimeMillis()
            recentStarts.addLast(startedNow)
            while (recentStarts.isNotEmpty() &&
                recentStarts.first() < startedNow - THROTTLE_WINDOW_MS
            ) {
                recentStarts.removeFirst()
            }
            scanning = true
            activeAddress = address
            activeMode = scanMode
            startedAt = System.currentTimeMillis()
            lastResultAt = 0L
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "missing scan permission", e)
            false
        }
    }

    /** Stop after a grace period, so a brief app switch does not cycle the scan. */
    fun stopSoon(delayMs: Long = GRACE_MS) {
        handler.removeCallbacks(deferredStop)
        handler.postDelayed(deferredStop, delayMs)
    }

    fun stop() = stopNow()

    private fun stopNow() {
        handler.removeCallbacks(deferredStop)
        wantScanning = false
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "missing scan permission on stop", e)
        }
        scanning = false
        activeAddress = null
        activeMode = null
        startedAt = 0L
    }

    /**
     * Advertised names are arbitrary bytes. Some devices broadcast control
     * characters or padding that survive trim() and render as an invisible
     * title, so require at least one visible character before trusting it.
     */
    private fun displayName(raw: String?): String =
        raw?.filterNot { it.isISOControl() }
            ?.trim()
            ?.takeIf { candidate -> candidate.any { it.isLetterOrDigit() } }
            ?: "(unnamed)"

    /** Drop devices we haven't heard from recently so the list reflects reality. */
    /**
     * @param millis generous by design: plenty of devices advertise only every
     * 10-30s, and dropping them between beacons makes the list flicker.
     */
    fun expireOlderThan(millis: Long) {
        val cutoff = System.currentTimeMillis() - millis
        val kept = _sightings.value.filterValues { it.seenAt >= cutoff }
        if (kept.size != _sightings.value.size) _sightings.value = kept
    }

    companion object {
        private const val TAG = "TrackrScanner"

        /** Keep a registration alive this long after a stop request. */
        private const val GRACE_MS = 45_000L

        /** The platform's budget: five scan starts per thirty seconds. */
        private const val THROTTLE_WINDOW_MS = 30_000L
        private const val THROTTLE_MAX_STARTS = 5
    }
}
