package com.agilesalt.trackrfinder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SavedDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun rememberedTrackerRemainsVisibleWithAnUnrecognizedAdvertisedName() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        val address = "AA:BB:CC:DD:EE:FF"
        val name = "Backpack".toByteArray()
        val bytes = byteArrayOf((name.size + 1).toByte(), 9) + name + byteArrayOf(0)
        val record = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, bytes) as ScanRecord
        val result = ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address), record, -60, 1L)

        fun discover(): Sighting? {
            val scanner = TrackrScanner(context)
            val callback = TrackrScanner::class.java.getDeclaredField("callback")
                .apply { isAccessible = true }.get(scanner) as ScanCallback
            callback.onScanResult(0, result)
            val sighting = scanner.sightings.value[address]
            scanner.release()
            return sighting
        }

        assertNull(discover())
        prefs.rememberDevice(address)
        assertTrue(Prefs(context).isRememberedDevice(address))
        assertEquals("Backpack", discover()?.name)
        assertEquals(MatchReason.SAVED_DEVICE, discover()?.matchReason)
    }

}
