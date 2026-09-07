package com.agilesalt.trackrfinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothStatusCodes
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
    data class Success(
        val batteryPct: Int? = null,
        val details: DeviceDetails? = null,
        val deviceName: String? = null,
    ) : RingResult

    /** Connected and enumerated: the device has no Immediate Alert. */
    data object Unsupported : RingResult

    data class Failure(val reason: String) : RingResult
}

/**
 * One connection at a time for alerts, battery, device details, or verified name
 * writes. Details and battery requests never write to Immediate Alert.
 */
@SuppressLint("MissingPermission")
class Ringer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var done = false
    private var onResult: ((RingResult) -> Unit)? = null
    private enum class Operation { ALERT, BATTERY, DETAILS, RENAME }
    private var operation = Operation.ALERT
    private var pendingName: String? = null
    private val detailQueue = ArrayDeque<DetailField>()
    private val detailValues = mutableMapOf<DetailField, String>()
    private var currentDetail: DetailField? = null
    private var detailsDiscovered = false
    private var canRename = false
    private val timeout = Runnable {
        if (operation == Operation.DETAILS && detailsDiscovered) finishDetails(false)
        else finish(RingResult.Failure(
            if (operation == Operation.RENAME)
                "Could not verify the device name. Refresh details before trying again."
            else "Timed out -- out of range?"
        ))
    }

    val busy: Boolean get() = gatt != null

    fun ring(address: String, level: Byte = Trackr.ALERT_HIGH, callback: (RingResult) -> Unit) =
        connect(address, level, callback)

    fun checkBattery(address: String, callback: (RingResult) -> Unit) =
        connect(address, null, callback, Operation.BATTERY)

    fun readDetails(address: String, callback: (RingResult) -> Unit) =
        connect(address, null, callback, Operation.DETAILS)

    fun renameDevice(address: String, name: String, callback: (RingResult) -> Unit) {
        val clean = name.trim()
        HardwareName.error(clean)?.let { callback(RingResult.Failure(it)); return }
        connect(address, null, callback, Operation.RENAME, clean)
    }

    private fun connect(
        address: String, level: Byte?, callback: (RingResult) -> Unit,
        requestedOperation: Operation = Operation.ALERT, name: String? = null,
    ) {
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
        operation = requestedOperation
        pendingName = name
        pendingLevel = level
        detailQueue.clear()
        detailValues.clear()
        currentDetail = null
        detailsDiscovered = false
        canRename = false
        onResult = callback
        try {
            // minSdk is 26: serialize callbacks with UI requests and timeouts.
            gatt = device.connectGatt(context, false, gattCallback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, main)
        } catch (e: SecurityException) {
            finish(RingResult.Failure("Missing Bluetooth connect permission"))
            return
        }
        armTimeout(TIMEOUT_MS)
    }

    fun stopRinging(address: String, callback: (RingResult) -> Unit) =
        ring(address, Trackr.ALERT_OFF, callback)

    /** Alert level used only by the ALERT operation. */
    private var pendingLevel: Byte? = Trackr.ALERT_HIGH
    private var battery: Int? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (done || g !== gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!g.discoverServices()) finish(RingResult.Failure("Service discovery refused"))
                }
                BluetoothProfile.STATE_DISCONNECTED ->
                    if (!done) {
                        if (operation == Operation.DETAILS && detailsDiscovered) finishDetails(false)
                        else finish(RingResult.Failure(
                            if (operation == Operation.RENAME)
                                "Disconnected before the name could be verified. Refresh details."
                            else "Disconnected (status $status)"
                        ))
                    }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (done || g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(RingResult.Failure("Service discovery failed ($status)")); return
            }
            if (operation == Operation.DETAILS) {
                detailsDiscovered = true
                val name = g.getService(Trackr.GENERIC_ACCESS)?.getCharacteristic(Trackr.DEVICE_NAME)
                canRename = name != null &&
                    name.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 &&
                    name.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                detailQueue.addAll(DetailField.entries)
                readNextDetail(g)
                return
            }
            if (operation == Operation.RENAME) {
                val name = g.getService(Trackr.GENERIC_ACCESS)?.getCharacteristic(Trackr.DEVICE_NAME)
                if (name == null || name.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
                    name.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0
                ) {
                    finish(RingResult.Failure("This tracker does not support verified hardware renaming."))
                    return
                }
                armTimeout(READ_TIMEOUT_MS)
                if (!writeValue(g, name, pendingName!!.toByteArray(Charsets.UTF_8),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) {
                    finish(RingResult.Failure("Device name write was rejected."))
                }
                return
            }
            val level = pendingLevel
            if (level == null) {
                readBattery(g)
                return
            }
            val alert = g.getService(Trackr.IMMEDIATE_ALERT)
                ?.getCharacteristic(Trackr.ALERT_LEVEL)
            if (alert == null) {
                finish(RingResult.Unsupported); return
            }
            writeAlert(g, alert, level)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            if (done || g !== gatt) return
            if (operation == Operation.RENAME) {
                if (ch.uuid != Trackr.DEVICE_NAME) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(RingResult.Failure("Tracker rejected the name change (status $status). " +
                        "Use a phone nickname if hardware renaming is not allowed."))
                    return
                }
                armTimeout(READ_TIMEOUT_MS)
                if (!g.readCharacteristic(ch)) {
                    finish(RingResult.Failure("Name write was accepted but could not be verified. Refresh details."))
                }
                return
            }
            if (operation != Operation.ALERT || ch.uuid != Trackr.ALERT_LEVEL) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(RingResult.Failure("Alert write failed (status $status)"))
                return
            }
            // The alert write succeeded; battery is a bonus.
            readBattery(g)
        }

        // API 33+
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) = handleRead(g, ch, value, status)

        @Deprecated("Pre-API-33 callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) = handleRead(g, ch, ch.value ?: ByteArray(0), status)
    }

    private fun readBattery(g: BluetoothGatt) {
        val ch = g.getService(Trackr.BATTERY_SERVICE)?.getCharacteristic(Trackr.BATTERY_LEVEL)
        if (ch == null || !g.readCharacteristic(ch)) {
            finish(RingResult.Success(null))
        }
    }

    private fun handleRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (done || g !== gatt) return
        if (operation == Operation.DETAILS) {
            val field = currentDetail ?: return
            if (ch.uuid != field.characteristicUuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                field.decode(value)?.let { detailValues[field] = it }
            }
            readNextDetail(g)
            return
        }
        if (operation == Operation.RENAME) {
            if (ch.uuid != Trackr.DEVICE_NAME) return
            val actual = value.toString(Charsets.UTF_8).trimEnd('\u0000')
            if (status == BluetoothGatt.GATT_SUCCESS && actual == pendingName) {
                finish(RingResult.Success(deviceName = actual))
            } else {
                finish(RingResult.Failure("The tracker did not confirm the new name. Refresh details."))
            }
            return
        }
        if (ch.uuid != Trackr.BATTERY_LEVEL) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // The standard defines one unsigned byte in 0..100. Some trackers
            // return 127; preserve that as unavailable rather than inventing 100%.
            battery = value.singleOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }
        }
        finish(RingResult.Success(battery))
    }

    private fun readNextDetail(g: BluetoothGatt) {
        currentDetail = null
        while (detailQueue.isNotEmpty()) {
            val field = detailQueue.removeFirst()
            val ch = g.getService(field.serviceUuid)?.getCharacteristic(field.characteristicUuid)
                ?: continue
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) continue
            currentDetail = field
            armTimeout(READ_TIMEOUT_MS)
            if (g.readCharacteristic(ch)) return
            currentDetail = null
        }
        finishDetails(true)
    }

    private fun finishDetails(complete: Boolean) = finish(RingResult.Success(
        details = DeviceDetails(detailValues.toMap(), canRename, complete),
    ))

    private fun armTimeout(millis: Long) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, millis)
    }

    @Suppress("DEPRECATION")
    private fun writeAlert(g: BluetoothGatt, ch: BluetoothGattCharacteristic, level: Byte) {
        // Honour the advertised write type rather than assuming the spec's.
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        if (!writeValue(g, ch, byteArrayOf(level), type)) {
            finish(RingResult.Failure("Write rejected by the stack"))
        }
    }

    @Suppress("DEPRECATION")
    private fun writeValue(g: BluetoothGatt, ch: BluetoothGattCharacteristic, payload: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = payload
            g.writeCharacteristic(ch)
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
        private const val READ_TIMEOUT_MS = 6_000L
    }
}
