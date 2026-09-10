package com.agilesalt.trackrfinder

import android.app.Service
import android.bluetooth.*
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [DeviceGattShadow::class])
class TrackerAlertServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test fun notificationOffPersistsPendingCleanupWithoutStoppingPhoneAlerts() {
        val prefs = Prefs(context)
        prefs.watchedAddress = address
        prefs.watchEnabled = true
        prefs.trackerAlertAddress = address
        prefs.trackerAlertEnabled = true
        prefs.trackerAlertNeedsDisarm = true
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_OFF)
        val controller = Robolectric.buildService(TrackerAlertService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(context, TrackerAlertService::class.java)
                .setAction(TrackerAlertService.ACTION_OFF), 0, 1)
            val restored = Prefs(context)
            assertFalse(restored.trackerAlertEnabled)
            assertTrue(restored.trackerAlertNeedsDisarm)
            assertTrue(restored.watchEnabled)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            assertEquals(TrackerAlertPhase.DISARMING, TrackerAlertService.state.value.phase)
        } finally { controller.destroy() }
    }

    @Test fun bootRestartsPendingOffEvenWhenBothAlertsAreSwitchedOff() {
        val prefs = Prefs(context)
        prefs.watchEnabled = false
        prefs.trackerAlertAddress = address
        prefs.trackerAlertEnabled = false
        prefs.trackerAlertNeedsDisarm = true
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(context).nextStartedService
        assertEquals(TrackerAlertService::class.java.name, started.component?.className)
        assertNull(shadowOf(context).nextStartedService)
        assertFalse(prefs.trackerAlertEnabled)
    }

    @Test fun stoppingBeforeAnyAlarmWriteFinishesWithoutConnecting() {
        val prefs = Prefs(context)
        prefs.trackerAlertAddress = address
        prefs.trackerAlertEnabled = true
        prefs.trackerAlertNeedsDisarm = false
        val controller = Robolectric.buildService(TrackerAlertService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(
                Intent(context, TrackerAlertService::class.java).setAction(TrackerAlertService.ACTION_OFF), 0, 1))
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertNull(prefs.trackerAlertAddress)
        } finally { controller.destroy() }
    }

    @Test fun heldConnectionPreventsFalsePhoneAlarmWithoutAdvertisementsAndModesStopIndependently() {
        val prefs = Prefs(context)
        prefs.trackerAlertAddress = address
        prefs.trackerAlertEnabled = true
        prefs.watchedAddress = address
        prefs.watchEnabled = true
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter.getRemoteDevice(address)).setBondState(BluetoothDevice.BOND_BONDED)
        val trackerController = Robolectric.buildService(TrackerAlertService::class.java).create()
        val watchController = Robolectric.buildService(WatchService::class.java).create()
        try {
            val service = trackerController.get()
            service.onStartCommand(null, 0, 1)
            val connection = field(service, "connection") as TrackerAlertConnection
            val gatt = field(connection, "gatt") as BluetoothGatt
            val callback = field(connection, "callback") as BluetoothGattCallback
            val gattShadow: DeviceGattShadow = Shadow.extract(gatt)
            val linkService = BluetoothGattService(Trackr.LINK_LOSS, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val ch = BluetoothGattCharacteristic(Trackr.ALERT_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
            linkService.addCharacteristic(ch)
            gattShadow.addDiscoverableService(linkService)
            callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            callback.onCharacteristicWrite(gatt, ch, BluetoothGatt.GATT_SUCCESS)
            callback.onCharacteristicRead(gatt, ch, byteArrayOf(2), BluetoothGatt.GATT_SUCCESS)
            assertTrue(TrackerAlertService.isConnected(address))
            watchController.get().onStartCommand(null, 0, 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(70))
            assertFalse(WatchService.outOfRange.value!!)
            assertTrue(System.currentTimeMillis() - prefs.lastSeenAt < 6000)

            watchController.get().onStartCommand(Intent(context, WatchService::class.java)
                .setAction(WatchService.ACTION_STOP), 0, 2)
            assertFalse(prefs.watchEnabled)
            assertTrue(prefs.trackerAlertEnabled)
            assertTrue(TrackerAlertService.isConnected(address))

            service.onStartCommand(Intent(context, TrackerAlertService::class.java)
                .setAction(TrackerAlertService.ACTION_OFF), 0, 2)
            callback.onCharacteristicWrite(gatt, ch, BluetoothGatt.GATT_SUCCESS)
            callback.onCharacteristicRead(gatt, ch, byteArrayOf(0), BluetoothGatt.GATT_SUCCESS)
            assertFalse(TrackerAlertService.isConnected(address))
            assertFalse(prefs.trackerAlertNeedsDisarm)
            assertTrue(gattShadow.isClosed)
        } finally {
            watchController.destroy()
            trackerController.destroy()
        }
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
}
