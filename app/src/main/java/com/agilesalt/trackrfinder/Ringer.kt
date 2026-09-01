package com.agilesalt.trackrfinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

sealed interface RingResult {
    data class Success(val batteryPct: Int?) : RingResult

    /**
     * Connected and enumerated services, but the device has no Immediate Alert.
     * A definitive answer -- unlike Failure, which may just be range or timing.
     */
    data object Unsupported : RingResult

    data class Failure(val reason: String) : RingResult
}

/**
 * Connects, writes one byte to the Immediate Alert Service, opportunistically
 * reads the battery level, and disconnects. No bonding or pairing is involved --
 * the Find Me profile is unauthenticated by design.
 */
@SuppressLint("MissingPermission")
class Ringer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var done = false
    private var onResult: ((RingResult) -> Unit)? = null

    val busy: Boolean get() = gatt != null

    fun ring(address: String, level: Byte = Trackr.ALERT_HIGH, callback: (RingResult) -> Unit) {
        if (busy) {
            callback(RingResult.Failure("Already talking to a device"))
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val device: BluetoothDevice? = runCatching {
            manager?.adapter?.getRemoteDevice(address)
        }.getOrNull()
        if (device == null) {
            callback(RingResult.Failure("Bluetooth unavailable, or bad address"))
            return
        }

        done = false
        onResult = callback
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            finish(RingResult.Failure("Missing Bluetooth connect permission"))
            return
        }
        main.postDelayed({ finish(RingResult.Failure("Timed out -- out of range?")) }, TIMEOUT_MS)
        this.pendingLevel = level
    }

    fun stopRinging(address: String, callback: (RingResult) -> Unit) =
        ring(address, Trackr.ALERT_OFF, callback)

    private var pendingLevel: Byte = Trackr.ALERT_HIGH
    private var battery: Int? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!g.discoverServices()) finish(RingResult.Failure("Service discovery refused"))
                }
                BluetoothProfile.STATE_DISCONNECTED ->
                    if (!done) finish(RingResult.Failure("Disconnected (status $status)"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(RingResult.Failure("Service discovery failed ($status)")); return
            }
            val alert = g.getService(Trackr.IMMEDIATE_ALERT)
                ?.getCharacteristic(Trackr.ALERT_LEVEL)
            if (alert == null) {
                finish(RingResult.Unsupported); return
            }
            writeAlert(g, alert, pendingLevel)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            // The ring already happened. Battery is a bonus; never fail on it.
            val batteryChar = g.getService(Trackr.BATTERY_SERVICE)
                ?.getCharacteristic(Trackr.BATTERY_LEVEL)
            if (batteryChar == null || !g.readCharacteristic(batteryChar)) {
                finish(RingResult.Success(null))
            }
        }

        // API 33+
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = handleRead(ch, value, status)

        @Deprecated("Pre-API-33 callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) = handleRead(ch, ch.value ?: ByteArray(0), status)
    }

    private fun handleRead(ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (ch.uuid == Trackr.BATTERY_LEVEL && status == BluetoothGatt.GATT_SUCCESS) {
            battery = value.firstOrNull()?.toInt()?.and(0xFF)
        }
        finish(RingResult.Success(battery))
    }

    @Suppress("DEPRECATION")
    private fun writeAlert(g: BluetoothGatt, ch: BluetoothGattCharacteristic, level: Byte) {
        // The Find Me spec mandates write-without-response, but honour whatever
        // the device actually advertises rather than assuming.
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val payload = byteArrayOf(level)

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = type
            ch.value = payload
            g.writeCharacteristic(ch)
        }
        if (!ok) finish(RingResult.Failure("Write rejected by the stack"))
    }

    private fun finish(result: RingResult) {
        if (done) return
        done = true
        main.removeCallbacksAndMessages(null)
        val cb = onResult
        onResult = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close failed", e)
        }
        gatt = null
        battery = null
        main.post { cb?.invoke(result) }
    }

    companion object {
        private const val TAG = "Ringer"
        private const val TIMEOUT_MS = 12_000L
    }
}
