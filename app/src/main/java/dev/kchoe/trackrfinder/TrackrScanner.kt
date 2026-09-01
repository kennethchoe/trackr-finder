package dev.kchoe.trackrfinder

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
        // device.name requires BLUETOOTH_CONNECT on some builds and can be null
        // before a connection; the advertised name in the scan record does not.
        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "adv ${result.device.address} name=${name ?: "<none>"} rssi=${result.rssi}")
        }
        if (!Trackr.isTrackr(name)) return

        val address = result.device.address
        _sightings.value = _sightings.value + (address to Sighting(
            address = address,
            name = name!!.trim(),
            rssi = result.rssi,
        ))
    }

    /** @param address pin the scan to one device (required for background scanning). */
    fun start(address: String? = null, lowPower: Boolean = false): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        if (scanning) stop()

        val filters = address?.let {
            listOf(ScanFilter.Builder().setDeviceAddress(it).build())
        } ?: emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(
                if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER
                else ScanSettings.SCAN_MODE_LOW_LATENCY
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

    /** Drop devices we haven't heard from recently so the list reflects reality. */
    fun expireOlderThan(millis: Long) {
        val cutoff = System.currentTimeMillis() - millis
        val kept = _sightings.value.filterValues { it.seenAt >= cutoff }
        if (kept.size != _sightings.value.size) _sightings.value = kept
    }

    companion object { private const val TAG = "TrackrScanner" }
}
