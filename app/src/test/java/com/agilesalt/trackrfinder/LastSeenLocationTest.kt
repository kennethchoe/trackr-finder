package com.agilesalt.trackrfinder

import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LastSeenLocationTest {
    private val now = 1_800_000_000_000L
    private val elapsedNanos = 600_000_000_000L

    private fun fix(ageMs: Long, accuracyMeters: Float = 10f) = Location("gps").apply {
        latitude = 30.2672
        longitude = -97.7431
        accuracy = accuracyMeters
        elapsedRealtimeNanos = elapsedNanos - ageMs * 1_000_000L
        time = now - ageMs
    }

    @Test fun rejectsOldInaccurateAndFutureFixes() {
        for (location in listOf(fix(60_000), fix(0, 500f), fix(-1),
            fix(0).apply { removeAccuracy() })) {
            assertNull(LastSeenLocation.select(listOf(location), now, now, elapsedNanos))
        }
    }

    @Test fun selectsFreshAccurateProviderEvenWhenNewerProviderIsInaccurate() {
        val good = fix(2_000)
        assertSame(good, LastSeenLocation.select(
            listOf(fix(60_000), good, fix(0, 500f)), now, now, elapsedNanos,
        ))
    }

    @Test fun cannotAttachNewPhonePositionToAnOldSighting() {
        assertNull(LastSeenLocation.select(listOf(fix(0)), now - 30_000, now, elapsedNanos))
    }

    @Test fun freshnessUsesMonotonicTimeInsteadOfTheFixWallClock() {
        val good = fix(2_000).apply { time = now - 3_600_000 }
        assertSame(good, LastSeenLocation.select(listOf(good), now, now, elapsedNanos))
        assertEquals(now - 2_000, LastSeenLocation.recordedAt(good, now, elapsedNanos))
    }

    @Test fun savedFixRemainsHistoricalButDoesNotFollowLaterSightings() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.lastSeenAt = now
        prefs.saveLocation(30.2672, -97.7431, now - 2_000)
        assertTrue(Prefs(RuntimeEnvironment.getApplication()).hasLocation)

        prefs.lastSeenAt = now + 30_000
        assertFalse(prefs.hasLocation)
    }

    @Test fun legacyCoordinatesWithoutAFixTimestampAreNotTrusted() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.lastSeenAt = now
        prefs.lastLat = 30.2672
        prefs.lastLon = -97.7431
        assertFalse(prefs.hasLocation)
    }

    @Test fun forgettingTagRemovesItsLocation() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.lastSeenAt = now
        prefs.saveLocation(30.2672, -97.7431, now)
        prefs.forgetWatch()
        prefs.lastSeenAt = now
        assertFalse(prefs.hasLocation)
    }
}
