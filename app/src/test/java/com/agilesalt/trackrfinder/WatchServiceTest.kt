package com.agilesalt.trackrfinder

import android.app.Service
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WatchServiceTest {
    @Test fun notificationStopPersistsAcrossServiceRecreationAndReboot() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = Prefs(context)
        prefs.watchedAddress = "AA:BB:CC:DD:EE:FF"
        prefs.watchedName = "Keys"
        prefs.watchEnabled = true
        prefs.lastSeenAt = 1_800_000_000_000L
        prefs.saveLocation(30.2672, -97.7431, prefs.lastSeenAt)

        val controller = Robolectric.buildService(WatchService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent(context, WatchService::class.java).setAction(WatchService.ACTION_STOP), 0, 1,
        ))
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()

        val restored = Prefs(context)
        assertFalse(restored.watchEnabled)
        assertEquals("Keys", restored.watchedName)
        assertTrue(restored.hasLocation)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(context).nextStartedService)

        val restarted = Robolectric.buildService(WatchService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, restarted.get().onStartCommand(null, 0, 2))
        assertTrue(shadowOf(restarted.get()).isStoppedBySelf)
        restarted.destroy()
    }
}
