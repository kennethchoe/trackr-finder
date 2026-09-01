package com.agilesalt.trackrfinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
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

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _sightings = MutableStateFlow<Map<String, Sighting>>(emptyMap())
    val sightings: StateFlow<Map<String, Sighting>> = _sightings.asStateFlow()

    private var scanning = false

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
        // Blend into the previous value rather than replacing it, and carry
        // firstSeen forward so the sort key stays stable across updates.
        val smoothed = prev?.let {
            it.smoothedRssi + Sighting.SMOOTHING * (result.rssi - it.smoothedRssi)
        } ?: result.rssi.toFloat()

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
        if (scanning) stop()

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
            scanning = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "missing scan permission", e)
            false
        }
    }

    fun stop() {
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "missing scan permission on stop", e)
        }
        scanning = false
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

    companion object { private const val TAG = "TrackrScanner" }
}
