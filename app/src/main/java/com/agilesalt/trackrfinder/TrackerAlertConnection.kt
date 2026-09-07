package com.agilesalt.trackrfinder

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

internal enum class TrackerAlertPhase { OFF, CONNECTING, PAIRING, ARMING, READY, RECONNECTING, DISARMING, ERROR }

internal data class TrackerAlertState(
    val address: String? = null,
    val phase: TrackerAlertPhase = TrackerAlertPhase.OFF,
    val connected: Boolean = false,
    val enabled: Boolean = false,
    val message: String = "Off",
    val busy: Boolean = false,
)

/** Owns one long-lived GATT link. Every Link Loss write requires exact read-back. */
@SuppressLint("MissingPermission")
internal class TrackerAlertConnection(
    private val context: Context,
    val address: String,
    private val prefs: Prefs,
    private val onState: (TrackerAlertState) -> Unit,
    private val onFinished: () -> Unit,
) {
    private enum class Stage { IDLE, CONNECTING, PAIRING, DISCOVERING, WRITING, VERIFYING, READY, RINGING, WAITING, CLOSED }
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var stage = Stage.IDLE
    private var connected = false
    private var level: Byte = Trackr.ALERT_OFF
    private var linkLoss: BluetoothGattCharacteristic? = null
    private var ringCharacteristic: BluetoothGattCharacteristic? = null
    private var ringCallback: ((String?) -> Unit)? = null
    private val enabled get() = prefs.trackerAlertEnabled
    private val desiredLevel get() = if (enabled) Trackr.ALERT_HIGH else Trackr.ALERT_OFF
    private val timeout = Runnable { fail("Tracker did not respond. Bring it nearby to retry.") }
    private val retry = Runnable { start() }

    private val bondReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device.address == address) bondChanged(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
        }
    }

    init {
        ContextCompat.registerReceiver(context, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    fun start() {
        if (stage == Stage.CLOSED) return
        handler.removeCallbacks(retry)
        if (!enabled && !prefs.trackerAlertNeedsDisarm) {
            finishOff()
            return
        }
        if (stage == Stage.READY) {
            if (level != desiredLevel) writeLevel()
            return
        }
        // A toggle during a write/read is applied after that operation completes.
        if (gatt != null) {
            if (!enabled) emit(TrackerAlertPhase.DISARMING, "Turning off… waiting for the tracker")
            return
        }
        stage = Stage.CONNECTING
        emit(if (enabled) TrackerAlertPhase.CONNECTING else TrackerAlertPhase.DISARMING,
            if (enabled) "Connecting… keep the tracker nearby" else "Turning off… bring the tracker nearby")
        deadline(45_000)
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                fail("Waiting for Bluetooth. The tracker may still sound its alarm.")
                return
            }
            gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback,
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
            if (gatt == null) fail("Could not connect to the tracker.")
        } catch (e: Exception) {
            fail("Could not connect: ${e.message}")
        }
    }

    /** Called after the service persists the user's new desired state. */
    fun settingsChanged() = start()

    internal fun bondChanged(bondState: Int) {
        if (stage != Stage.PAIRING) return
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> discover()
            BluetoothDevice.BOND_NONE -> fail("Pairing failed. Keep the tracker nearby and tap Retry.", retryable = false)
        }
    }

    private fun discover() {
        val g = gatt ?: return
        stage = Stage.DISCOVERING
        emit(if (enabled) TrackerAlertPhase.ARMING else TrackerAlertPhase.DISARMING,
            if (enabled) "Checking tracker alarm…" else "Turning off… checking the tracker")
        deadline(30_000)
        attempt("Could not discover tracker services.") { g.discoverServices() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(if (enabled) "Connection lost. The tracker may be sounding. Reconnecting…"
                    else "Turning off… bring the tracker nearby to finish")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (stage != Stage.CONNECTING) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Connection failed (status $status).")
                    return
                }
                connected = true
                if (g.device.bondState == BluetoothDevice.BOND_BONDED) discover()
                else {
                    stage = Stage.PAIRING
                    emit(TrackerAlertPhase.PAIRING, "Pairing… accept Android’s pairing request if shown")
                    deadline(60_000)
                    if (g.device.bondState != BluetoothDevice.BOND_BONDING) {
                        attempt("Could not start pairing. Tap Retry.", retryable = false) { g.device.createBond() }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt || stage != Stage.DISCOVERING) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not discover tracker services (status $status).")
                return
            }
            val ch = g.getService(Trackr.LINK_LOSS)?.getCharacteristic(Trackr.ALERT_LEVEL)
            val flags = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE
            if (ch == null || ch.properties and flags != flags) {
                fail("This tracker does not offer a readable and writable lost-connection alarm.", retryable = false)
                return
            }
            linkLoss = ch
            writeLevel()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            if (stage == Stage.RINGING && ch === ringCharacteristic) {
                handler.removeCallbacks(timeout)
                stage = Stage.READY
                val cb = ringCallback
                ringCallback = null
                ringCharacteristic = null
                emitReady()
                cb?.invoke(if (status == BluetoothGatt.GATT_SUCCESS) null else "Ring command failed (status $status).")
                if (level != desiredLevel) writeLevel()
                return
            }
            if (stage != Stage.WRITING || ch !== linkLoss) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Tracker rejected the alarm setting (status $status).", retryable = false)
                return
            }
            stage = Stage.VERIFYING
            deadline(20_000)
            attempt("Could not verify the tracker’s alarm setting.") { g.readCharacteristic(ch) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) readComplete(g, ch, ch.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readComplete(g, ch, value, status)
        }
    }

    private fun readComplete(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (g !== gatt || stage != Stage.VERIFYING || ch !== linkLoss) return
        if (status != BluetoothGatt.GATT_SUCCESS || !value.contentEquals(byteArrayOf(level))) {
            fail("The tracker did not confirm the alarm setting. Tap Retry.", retryable = false)
            return
        }
        handler.removeCallbacks(timeout)
        // A toggle may have arrived while this operation was in flight.
        if (level != desiredLevel) {
            writeLevel()
        } else if (level == Trackr.ALERT_OFF) {
            finishOff()
        } else {
            stage = Stage.READY
            emitReady()
        }
    }

    private fun writeLevel() {
        val g = gatt ?: return
        val ch = linkLoss ?: return
        level = desiredLevel
        // A crash or lost callback after this write must leave a cleanup record.
        if (level != Trackr.ALERT_OFF) prefs.trackerAlertNeedsDisarm = true
        stage = Stage.WRITING
        emit(if (enabled) TrackerAlertPhase.ARMING else TrackerAlertPhase.DISARMING,
            if (enabled) "Enabling tracker alarm…" else "Turning off… waiting for confirmation")
        deadline(20_000)
        attempt("Could not write the tracker alarm setting.") {
            write(g, ch, byteArrayOf(level), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    /** Ring and Stop share the held connection; neither disconnects the watch. */
    fun ring(stop: Boolean, result: (String?) -> Unit) {
        if (stage != Stage.READY || !enabled) {
            result("Wait until the tracker alarm is ready.")
            return
        }
        val g = gatt ?: return
        val ch = g.getService(Trackr.IMMEDIATE_ALERT)?.getCharacteristic(Trackr.ALERT_LEVEL)
        if (ch == null) {
            result("This tracker does not support Ring / Stop.")
            return
        }
        val type = when {
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> { result("This tracker does not support Ring / Stop."); return }
        }
        ringCallback = result
        ringCharacteristic = ch
        stage = Stage.RINGING
        emit(TrackerAlertPhase.READY, "Ready · tracker will beep if the connection is lost")
        deadline(15_000)
        attempt("Ring command could not be sent.") {
            write(g, ch, byteArrayOf(if (stop) Trackr.ALERT_OFF else Trackr.ALERT_HIGH), type)
        }
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
        else { ch.writeType = type; ch.value = bytes; g.writeCharacteristic(ch) }

    private fun attempt(message: String, retryable: Boolean = true, action: () -> Boolean) {
        try { if (!action()) fail(message, retryable) }
        catch (e: Exception) { fail("$message ${e.message.orEmpty()}", retryable) }
    }

    private fun deadline(ms: Long) {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, ms)
    }

    private fun emitReady() = emit(TrackerAlertPhase.READY, "Ready · tracker will beep if the connection is lost")

    private fun emit(phase: TrackerAlertPhase, message: String) = onState(
        TrackerAlertState(address, phase, connected, enabled, message, stage == Stage.RINGING)
    )

    private fun fail(message: String, retryable: Boolean = true) {
        if (stage == Stage.CLOSED) return
        closeGatt()
        stage = Stage.WAITING
        emit(if (retryable) {
            if (enabled) TrackerAlertPhase.RECONNECTING else TrackerAlertPhase.DISARMING
        } else TrackerAlertPhase.ERROR, message)
        if (retryable) handler.postDelayed(retry, 15_000)
    }

    private fun finishOff() {
        closeGatt()
        stage = Stage.IDLE
        prefs.clearTrackerAlert()
        onState(TrackerAlertState())
        onFinished()
    }

    private fun closeGatt() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(retry)
        val old = gatt
        gatt = null
        connected = false
        linkLoss = null
        ringCharacteristic = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        val cb = ringCallback
        ringCallback = null
        cb?.invoke("Connection closed before the ring command completed.")
    }

    fun close() {
        stage = Stage.CLOSED
        closeGatt()
        runCatching { context.unregisterReceiver(bondReceiver) }
    }
}
