package com.agilesalt.trackrfinder

import android.location.Location
import kotlin.math.abs

/** A cached phone fix is useful only if it belongs to the tracker sighting. */
internal object LastSeenLocation {
    // Conservative limits: a missing map pin is preferable to an unrelated one.
    private const val MAX_AGE_MS = 10_000L
    private const val MAX_ACCURACY_METERS = 50f

    fun matchesSighting(recordedAt: Long, seenAt: Long): Boolean =
        recordedAt > 0 && seenAt > 0 && abs(recordedAt - seenAt) <= MAX_AGE_MS

    fun recordedAt(location: Location, now: Long, elapsedNanos: Long): Long =
        now - (elapsedNanos - location.elapsedRealtimeNanos) / 1_000_000L

    fun select(
        locations: List<Location>, seenAt: Long, now: Long, elapsedNanos: Long,
    ): Location? = locations.filter { location ->
        val ageNanos = elapsedNanos - location.elapsedRealtimeNanos
        location.elapsedRealtimeNanos > 0 &&
            ageNanos in 0..MAX_AGE_MS * 1_000_000L &&
            now - seenAt in 0..MAX_AGE_MS &&
            location.hasAccuracy() && location.accuracy in 0f..MAX_ACCURACY_METERS &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 &&
            matchesSighting(recordedAt(location, now, elapsedNanos), seenAt)
    }.maxByOrNull { it.elapsedRealtimeNanos }
}
