package com.agilesalt.trackrfinder

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.os.Build
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@Implements(BluetoothGatt::class)
class DeviceGattShadow : BatteryGattShadow() {
    var written: BluetoothGattCharacteristic? = null
    var payload: ByteArray? = null

    @Implementation
    protected override fun writeCharacteristic(ch: BluetoothGattCharacteristic, value: ByteArray, type: Int): Int {
        written = ch
        payload = value.copyOf()
        return BluetoothGatt.GATT_SUCCESS
    }

    @Suppress("DEPRECATION")
    @Implementation
    protected override fun writeCharacteristic(ch: BluetoothGattCharacteristic): Boolean {
        written = ch
        payload = ch.value.copyOf()
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [DeviceGattShadow::class])
class DeviceOperationsTest {
    private class Session(start: (Ringer, (RingResult) -> Unit) -> Unit) {
        val ringer = Ringer(RuntimeEnvironment.getApplication())
        var result: RingResult? = null
        val gatt: BluetoothGatt
        val callback: BluetoothGattCallback
        val shadow: DeviceGattShadow
        val characteristics = mutableMapOf<DetailField, BluetoothGattCharacteristic>()

        init {
            start(ringer) { result = it }
            gatt = Ringer::class.java.getDeclaredField("gatt").apply { isAccessible = true }
                .get(ringer) as BluetoothGatt
            callback = Ringer::class.java.getDeclaredField("gattCallback").apply { isAccessible = true }
                .get(ringer) as BluetoothGattCallback
            shadow = Shadow.extract(gatt)
        }

        fun addFields(vararg fields: DetailField, writableName: Boolean = true) {
            for ((serviceUuid, group) in fields.groupBy { it.serviceUuid }) {
                val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                group.forEach { field ->
                    val flags = BluetoothGattCharacteristic.PROPERTY_READ or
                        if (field == DetailField.NAME && writableName) BluetoothGattCharacteristic.PROPERTY_WRITE else 0
                    val ch = BluetoothGattCharacteristic(field.characteristicUuid, flags,
                        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
                    characteristics[field] = ch
                    service.addCharacteristic(ch)
                }
                shadow.addDiscoverableService(service)
            }
        }

        @Suppress("DEPRECATION")
        fun read(field: DetailField, value: String, status: Int = BluetoothGatt.GATT_SUCCESS) {
            val ch = characteristics.getValue(field)
            val bytes = value.toByteArray()
            if (Build.VERSION.SDK_INT >= 33) callback.onCharacteristicRead(gatt, ch, bytes, status)
            else {
                ch.value = bytes
                callback.onCharacteristicRead(gatt, ch, status)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun writeComplete(status: Int) {
            callback.onCharacteristicWrite(gatt, shadow.written!!, status)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun detailsReadsAreSequentialAndReadOnlyWithFailuresLeftUnavailable() {
        val s = Session { r, cb -> r.readDetails(ADDRESS, cb) }
        s.addFields(DetailField.NAME, DetailField.MODEL, DetailField.FIRMWARE)
        s.gatt.discoverServices()
        assertEquals(Trackr.DEVICE_NAME, s.shadow.requestedRead)
        assertNull(s.result)
        s.read(DetailField.NAME, "tkr")
        assertEquals(DetailField.MODEL.characteristicUuid, s.shadow.requestedRead)
        s.read(DetailField.MODEL, "ignored", BluetoothGatt.GATT_FAILURE)
        assertEquals(DetailField.FIRMWARE.characteristicUuid, s.shadow.requestedRead)
        s.read(DetailField.FIRMWARE, "1.2.3")
        val details = (s.result as RingResult.Success).details!!
        assertEquals("tkr", details.values[DetailField.NAME])
        assertEquals("1.2.3", details.values[DetailField.FIRMWARE])
        assertNull(details.values[DetailField.MODEL])
        assertTrue(details.canRename)
        assertTrue(details.complete)
        assertNull(s.shadow.written)
        assertTrue(s.shadow.isClosed)
    }

    @Test fun detailsTimeoutPreservesCompletedFieldsAndClosesConnection() {
        val s = Session { r, cb -> r.readDetails(ADDRESS, cb) }
        s.addFields(DetailField.NAME, DetailField.MODEL)
        s.gatt.discoverServices()
        s.read(DetailField.NAME, "tkr")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        val details = (s.result as RingResult.Success).details!!
        assertFalse(details.complete)
        assertEquals("tkr", details.values[DetailField.NAME])
        assertTrue(s.shadow.isClosed)
    }

    @Test fun renameWritesOnlyDeviceNameAndRequiresExactReadback() {
        val s = Session { r, cb -> r.renameDevice(ADDRESS, "Keys", cb) }
        s.addFields(DetailField.NAME)
        s.gatt.discoverServices()
        assertEquals(Trackr.DEVICE_NAME, s.shadow.written!!.uuid)
        assertArrayEquals("Keys".toByteArray(), s.shadow.payload)
        assertNull(s.result)
        s.writeComplete(BluetoothGatt.GATT_SUCCESS)
        assertEquals(Trackr.DEVICE_NAME, s.shadow.requestedRead)
        assertNull(s.result)
        s.read(DetailField.NAME, "Keys")
        assertEquals("Keys", (s.result as RingResult.Success).deviceName)
        assertTrue(s.shadow.isClosed)
    }

    @Test fun rejectedNameWriteReportsFailureWithoutVerificationRead() {
        val s = Session { r, cb -> r.renameDevice(ADDRESS, "Keys", cb) }
        s.addFields(DetailField.NAME)
        s.gatt.discoverServices()
        s.writeComplete(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION)
        assertTrue(s.result is RingResult.Failure)
        assertNull(s.shadow.requestedRead)
        assertTrue(s.shadow.isClosed)
    }

    @Test fun acceptedButIgnoredNameWriteDoesNotReportSuccess() {
        val s = Session { r, cb -> r.renameDevice(ADDRESS, "Keys", cb) }
        s.addFields(DetailField.NAME)
        s.gatt.discoverServices()
        s.writeComplete(BluetoothGatt.GATT_SUCCESS)
        s.read(DetailField.NAME, "tkr")
        assertTrue(s.result is RingResult.Failure)
    }

    @Test fun readOnlyNameDoesNotAttemptAWrite() {
        val s = Session { r, cb -> r.renameDevice(ADDRESS, "Keys", cb) }
        s.addFields(DetailField.NAME, writableName = false)
        s.gatt.discoverServices()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(s.shadow.written)
        assertTrue(s.result is RingResult.Failure)
    }

    @Test fun ringLevelsSendTheChosenAlertByte() {
        for (level in listOf(Trackr.ALERT_MILD, Trackr.ALERT_HIGH, Trackr.ALERT_OFF)) {
            val s = Session { r, cb -> r.ring(ADDRESS, level, cb) }
            val service = BluetoothGattService(Trackr.IMMEDIATE_ALERT, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(Trackr.ALERT_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            s.shadow.addDiscoverableService(service)
            s.gatt.discoverServices()
            assertEquals(Trackr.ALERT_LEVEL, s.shadow.written!!.uuid)
            assertArrayEquals(byteArrayOf(level), s.shadow.payload)
            s.writeComplete(BluetoothGatt.GATT_SUCCESS)
            assertEquals(RingResult.Success(null), s.result)
        }
    }

    private companion object { const val ADDRESS = "AA:BB:CC:DD:EE:FF" }
}
