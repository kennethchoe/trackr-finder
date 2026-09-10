package com.agilesalt.trackrfinder

import android.bluetooth.BluetoothAdapter
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
import org.robolectric.shadows.ShadowBluetoothGatt
import java.util.UUID

@Implements(BluetoothGatt::class)
open class BatteryGattShadow : ShadowBluetoothGatt() {
    var requestedRead: UUID? = null

    @Implementation
    protected fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        requestedRead = characteristic.uuid
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [BatteryGattShadow::class])
class RingerTest {
    private fun completeWrite(status: Int): RingResult? {
        val ringer = Ringer(RuntimeEnvironment.getApplication())
        var result: RingResult? = null
        val onResult: (RingResult) -> Unit = { result = it }
        Ringer::class.java.getDeclaredField("onResult").apply { isAccessible = true }
            .set(ringer, onResult)
        val callback = Ringer::class.java.getDeclaredField("gattCallback")
            .apply { isAccessible = true }.get(ringer) as BluetoothGattCallback
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        Ringer::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(ringer, gatt)
        val alert = BluetoothGattCharacteristic(
            Trackr.ALERT_LEVEL, BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        callback.onCharacteristicWrite(gatt, alert, status)
        shadowOf(Looper.getMainLooper()).idle()
        gatt.close()
        return result
    }

    @Test fun failedAlertWriteReportsFailure() {
        val result = completeWrite(BluetoothGatt.GATT_FAILURE)
        assertTrue(result is RingResult.Failure)
        assertTrue((result as RingResult.Failure).reason.contains("${BluetoothGatt.GATT_FAILURE}"))
    }

    @Test fun successfulAlertWithoutBatteryServiceStillSucceeds() {
        assertEquals(RingResult.Success(null), completeWrite(BluetoothGatt.GATT_SUCCESS))
    }

    private class BatterySession(withBatteryService: Boolean = true) {
        val ringer = Ringer(RuntimeEnvironment.getApplication())
        var result: RingResult? = null
        val gatt: BluetoothGatt
        val callback: BluetoothGattCallback
        val shadow: BatteryGattShadow
        val battery = BluetoothGattCharacteristic(
            Trackr.BATTERY_LEVEL, BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        init {
            ringer.checkBattery("AA:BB:CC:DD:EE:FF") { result = it }
            gatt = Ringer::class.java.getDeclaredField("gatt")
                .apply { isAccessible = true }.get(ringer) as BluetoothGatt
            callback = Ringer::class.java.getDeclaredField("gattCallback")
                .apply { isAccessible = true }.get(ringer) as BluetoothGattCallback
            shadow = Shadow.extract(gatt)
            if (withBatteryService) {
                val service = BluetoothGattService(
                    Trackr.BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY,
                )
                service.addCharacteristic(battery)
                shadow.addDiscoverableService(service)
            }
            // Include Immediate Alert to catch any accidental ring write.
            val alertService = BluetoothGattService(
                Trackr.IMMEDIATE_ALERT, BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
            alertService.addCharacteristic(BluetoothGattCharacteristic(
                Trackr.ALERT_LEVEL, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ))
            shadow.addDiscoverableService(alertService)
            gatt.discoverServices()
        }

        @Suppress("DEPRECATION")
        fun deliver(value: ByteArray, status: Int = BluetoothGatt.GATT_SUCCESS) {
            if (Build.VERSION.SDK_INT >= 33) {
                callback.onCharacteristicRead(gatt, battery, value, status)
            } else {
                battery.value = value
                callback.onCharacteristicRead(gatt, battery, status)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun batteryCheckReadsBatteryWithoutWritingAlertAndDisconnects() {
        val session = BatterySession()
        assertEquals(Trackr.BATTERY_LEVEL, session.shadow.requestedRead)
        assertNull(session.shadow.latestWrittenBytes)
        session.deliver(byteArrayOf(73))
        assertEquals(RingResult.Success(73), session.result)
        assertNull(session.shadow.latestWrittenBytes)
        assertTrue(session.shadow.isClosed)
        assertFalse(session.ringer.busy)
    }

    @Test fun invalidAndMalformedBatteryValuesAreUnavailable() {
        for (bytes in listOf(byteArrayOf(127), byteArrayOf(101), byteArrayOf(-1),
            byteArrayOf(), byteArrayOf(50, 0))) {
            val session = BatterySession()
            session.deliver(bytes)
            assertEquals(RingResult.Success(null), session.result)
        }
    }

    @Test fun zeroAndFullBatteryAreValid() {
        for (level in listOf(0, 100)) {
            val session = BatterySession()
            session.deliver(byteArrayOf(level.toByte()))
            assertEquals(RingResult.Success(level), session.result)
        }
    }

    @Test fun missingBatteryServiceIsUnavailableWithoutRinging() {
        val session = BatterySession(withBatteryService = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RingResult.Success(null), session.result)
        assertNull(session.shadow.latestWrittenBytes)
        assertTrue(session.shadow.isClosed)
    }

    @Test fun failedBatteryReadIsUnavailable() {
        val session = BatterySession()
        session.deliver(byteArrayOf(73), BluetoothGatt.GATT_FAILURE)
        assertEquals(RingResult.Success(null), session.result)
    }

    @Test fun batteryConnectionTimeoutReportsFailureAndDisconnects() {
        val session = BatterySession()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(13))
        assertTrue(session.result is RingResult.Failure)
        assertTrue(session.shadow.isClosed)
        assertFalse(session.ringer.busy)
    }
}
