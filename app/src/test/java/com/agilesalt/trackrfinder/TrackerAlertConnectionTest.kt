package com.agilesalt.trackrfinder

import android.bluetooth.*
import android.os.Build
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], shadows = [DeviceGattShadow::class])
class TrackerAlertConnectionTest {
    internal class Session(bonded: Boolean = true, servicePresent: Boolean = true, resumeOff: Boolean = false) : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        var state = TrackerAlertState()
        var finished = false
        val connection: TrackerAlertConnection
        var gatt: BluetoothGatt
        var shadow: DeviceGattShadow
        val callback: BluetoothGattCallback
        var linkLoss: BluetoothGattCharacteristic
        var immediate: BluetoothGattCharacteristic

        init {
            prefs.trackerAlertAddress = ADDRESS
            prefs.trackerAlertEnabled = !resumeOff
            prefs.trackerAlertNeedsDisarm = resumeOff
            val adapter = BluetoothAdapter.getDefaultAdapter()
            shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            val device = adapter.getRemoteDevice(ADDRESS)
            shadowOf(device).setCreatedBond(true)
            shadowOf(device).setBondState(if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE)
            connection = TrackerAlertConnection(context, ADDRESS, prefs, { state = it }, { finished = true })
            connection.start()
            gatt = field("gatt") as BluetoothGatt
            shadow = Shadow.extract(gatt)
            callback = field("callback") as BluetoothGattCallback
            linkLoss = characteristic()
            immediate = characteristic()
            if (servicePresent) addService(Trackr.LINK_LOSS, linkLoss)
            addService(Trackr.IMMEDIATE_ALERT, immediate)
            callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        }

        private fun field(name: String) = TrackerAlertConnection::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(connection)

        private fun characteristic() = BluetoothGattCharacteristic(Trackr.ALERT_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)

        private fun addService(uuid: java.util.UUID, ch: BluetoothGattCharacteristic) {
            val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(ch)
            shadow.addDiscoverableService(service)
        }

        fun reconnect() {
            connection.start()
            gatt = field("gatt") as BluetoothGatt
            shadow = Shadow.extract(gatt)
            linkLoss = characteristic()
            immediate = characteristic()
            addService(Trackr.LINK_LOSS, linkLoss)
            addService(Trackr.IMMEDIATE_ALERT, immediate)
            callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        }

        fun written(status: Int = BluetoothGatt.GATT_SUCCESS, ch: BluetoothGattCharacteristic = linkLoss) {
            callback.onCharacteristicWrite(gatt, ch, status)
        }

        @Suppress("DEPRECATION")
        fun read(value: ByteArray, status: Int = BluetoothGatt.GATT_SUCCESS, ch: BluetoothGattCharacteristic = linkLoss) {
            if (Build.VERSION.SDK_INT >= 33) callback.onCharacteristicRead(gatt, ch, value, status)
            else { ch.value = value; callback.onCharacteristicRead(gatt, ch, status) }
        }

        fun arm() { written(); read(byteArrayOf(2)) }
        fun off() { prefs.trackerAlertEnabled = false; connection.settingsChanged() }
        override fun close() = connection.close()
    }

    @Test fun onlyLinkLossIsArmedAndReadyRequiresVerifiedReadback() = Session().use { s ->
        assertSame(s.linkLoss, s.shadow.written)
        assertArrayEquals(byteArrayOf(2), s.shadow.payload)
        assertTrue(Prefs(s.context).trackerAlertNeedsDisarm)
        assertEquals(TrackerAlertPhase.ARMING, s.state.phase)
        s.written()
        assertEquals(TrackerAlertPhase.ARMING, s.state.phase)
        // Same UUID in Immediate Alert must not satisfy Link Loss verification.
        s.read(byteArrayOf(2), ch = s.immediate)
        assertEquals(TrackerAlertPhase.ARMING, s.state.phase)
        s.read(byteArrayOf(2))
        assertEquals(TrackerAlertPhase.READY, s.state.phase)
        assertTrue(s.state.connected)
        assertFalse(s.shadow.isClosed)
    }

    @Test fun ignoredOrMalformedReadbackNeverClaimsReady() {
        for (value in listOf(byteArrayOf(0), byteArrayOf(), byteArrayOf(2, 0))) {
            Session().use { s ->
                s.written()
                s.read(value)
                assertEquals(TrackerAlertPhase.ERROR, s.state.phase)
                assertTrue(s.prefs.trackerAlertNeedsDisarm)
                assertFalse(s.state.connected)
            }
        }
    }

    @Test fun rejectedWriteRetainsCleanupRecordAndReportsError() = Session().use { s ->
        s.written(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION)
        assertEquals(TrackerAlertPhase.ERROR, s.state.phase)
        assertTrue(s.prefs.trackerAlertNeedsDisarm)
        assertFalse(s.finished)
    }

    @Test fun offWaitsForReadbackBeforeClearingPreferencesAndDisconnecting() = Session().use { s ->
        s.arm()
        s.off()
        assertArrayEquals(byteArrayOf(0), s.shadow.payload)
        assertTrue(s.prefs.trackerAlertNeedsService)
        assertEquals(TrackerAlertPhase.DISARMING, s.state.phase)
        s.written()
        assertFalse(s.finished)
        s.read(byteArrayOf(0))
        assertTrue(s.finished)
        assertNull(s.prefs.trackerAlertAddress)
        assertFalse(s.prefs.trackerAlertNeedsService)
        assertTrue(s.shadow.isClosed)
    }

    @Test fun offDuringEnablingWriteIsAppliedAfterHighReadback() = Session().use { s ->
        s.off()
        assertArrayEquals(byteArrayOf(2), s.shadow.payload)
        s.written()
        s.read(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(0), s.shadow.payload)
        assertFalse(s.state.enabled)
        assertFalse(s.finished)
        s.written()
        s.read(byteArrayOf(0))
        assertTrue(s.finished)
    }

    @Test fun offDuringLostConnectionReconnectsToDisarmInsteadOfRearming() = Session().use { s ->
        s.arm()
        val old = s.gatt
        val oldCh = s.linkLoss
        s.callback.onConnectionStateChange(s.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(s.state.connected)
        assertEquals(TrackerAlertPhase.RECONNECTING, s.state.phase)
        s.prefs.trackerAlertEnabled = false
        s.reconnect()
        assertArrayEquals(byteArrayOf(0), s.shadow.payload)
        s.callback.onCharacteristicWrite(old, oldCh, BluetoothGatt.GATT_SUCCESS)
        assertEquals(TrackerAlertPhase.DISARMING, s.state.phase)
        assertFalse(s.finished)
        s.written()
        s.read(byteArrayOf(0))
        assertTrue(s.finished)
    }

    @Test fun pendingOffSurvivesRecreationAndNeverWritesHigh() = Session(resumeOff = true).use { s ->
        assertArrayEquals(byteArrayOf(0), s.shadow.payload)
        s.written()
        s.read(byteArrayOf(0))
        assertTrue(s.finished)
    }

    @Test fun lostConnectionRetriesAndVerifiesHighAgain() = Session().use { s ->
        s.arm()
        val old = s.gatt
        s.callback.onConnectionStateChange(old, 8, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(TrackerAlertPhase.RECONNECTING, s.state.phase)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
        s.reconnect()
        assertNotSame(old, s.gatt)
        assertArrayEquals(byteArrayOf(2), s.shadow.payload)
        assertEquals(TrackerAlertPhase.ARMING, s.state.phase)
        s.arm()
        assertEquals(TrackerAlertPhase.READY, s.state.phase)
    }

    @Test fun turningOffDuringRingWaitsForRingThenVerifiesOff() = Session().use { s ->
        s.arm()
        s.connection.ring(false) {}
        s.off()
        assertSame(s.immediate, s.shadow.written)
        s.written(ch = s.immediate)
        assertSame(s.linkLoss, s.shadow.written)
        assertArrayEquals(byteArrayOf(0), s.shadow.payload)
        s.written()
        s.read(byteArrayOf(0))
        assertTrue(s.finished)
    }

    @Test fun failedOffReadbackDoesNotPretendAlarmIsDisabled() = Session().use { s ->
        s.arm()
        s.off()
        s.written()
        s.read(byteArrayOf(2))
        assertEquals(TrackerAlertPhase.ERROR, s.state.phase)
        assertFalse(s.state.enabled)
        assertTrue(s.prefs.trackerAlertNeedsDisarm)
        assertFalse(s.finished)
    }

    @Test fun ringAndStopUseImmediateAlertWithoutClosingTheHeldLink() = Session().use { s ->
        s.arm()
        for (stop in listOf(false, true)) {
            var complete = false
            s.connection.ring(stop) { error -> assertNull(error); complete = true }
            assertSame(s.immediate, s.shadow.written)
            assertArrayEquals(byteArrayOf(if (stop) 0 else 2), s.shadow.payload)
            s.written(ch = s.immediate)
            assertTrue(complete)
            assertEquals(TrackerAlertPhase.READY, s.state.phase)
            assertFalse(s.shadow.isClosed)
            assertTrue(s.prefs.trackerAlertNeedsDisarm)
        }
    }

    @Test fun timeoutClosesConnectionButPreservesUncertainSetting() = Session().use { s ->
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertFalse(s.state.connected)
        assertTrue(s.shadow.isClosed)
        assertTrue(s.prefs.trackerAlertNeedsDisarm)
        assertEquals(TrackerAlertPhase.RECONNECTING, s.state.phase)
    }

    @Test fun unsupportedTrackerCanBeTurnedOffWithoutAWrite() = Session(servicePresent = false).use { s ->
        assertEquals(TrackerAlertPhase.ERROR, s.state.phase)
        assertNull(s.shadow.written)
        assertFalse(s.prefs.trackerAlertNeedsDisarm)
        s.off()
        assertTrue(s.finished)
    }

    @Test fun pairingMustCompleteBeforeDiscoveringAndArming() = Session(bonded = false).use { s ->
        assertEquals(TrackerAlertPhase.PAIRING, s.state.phase)
        assertNull(s.shadow.written)
        s.connection.bondChanged(BluetoothDevice.BOND_BONDED)
        assertSame(s.linkLoss, s.shadow.written)
        s.arm()
        assertEquals(TrackerAlertPhase.READY, s.state.phase)
    }

    @Test fun cancelledPairingDoesNotWriteAnAlarmSetting() = Session(bonded = false).use { s ->
        s.connection.bondChanged(BluetoothDevice.BOND_NONE)
        assertEquals(TrackerAlertPhase.ERROR, s.state.phase)
        assertFalse(s.prefs.trackerAlertNeedsDisarm)
        assertNull(s.shadow.written)
    }

    @Test fun lateCallbackAfterCloseCannotRearm() = Session().use { s ->
        s.connection.close()
        s.written()
        s.read(byteArrayOf(2))
        assertNotEquals(TrackerAlertPhase.READY, s.state.phase)
        assertTrue(s.shadow.isClosed)
    }

    internal companion object { const val ADDRESS = "AA:BB:CC:DD:EE:FF" }
}
