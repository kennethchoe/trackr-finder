package com.agilesalt.trackrfinder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothGatt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
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
}
