package dev.kchoe.trackrfinder

import java.util.UUID
import kotlin.math.pow

/**
 * The TrackR Pixel is not a proprietary device: it implements the Bluetooth SIG
 * "Find Me" profile. Ringing it is a single one-byte write to the standard
 * Immediate Alert Service. Protocol confirmed against
 * github.com/danielweidman/TrackR-Web-Bluetooth-API
 */
object Trackr {
    val IMMEDIATE_ALERT: UUID = uuid16("1802")
    val ALERT_LEVEL: UUID = uuid16("2A06")
    val BATTERY_SERVICE: UUID = uuid16("180F")
    val BATTERY_LEVEL: UUID = uuid16("2A19")

    /** Pixels advertise as "tkr" + a device-specific suffix. */
    const val NAME_PREFIX = "tkr"

    /**
     * Name prefixes of tags known to implement Find Me. Only a fallback: the
     * reliable signal is the device advertising Immediate Alert directly, but
     * plenty of cheap tags omit it from the advertisement and expose it only
     * after connecting, where a scan filter cannot see it.
     */
    val KNOWN_TAG_PREFIXES = listOf("tkr", "itag", "tag", "nut", "keyfinder")

    const val ALERT_OFF: Byte = 0x00
    const val ALERT_MILD: Byte = 0x01
    const val ALERT_HIGH: Byte = 0x02

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000${short.lowercase()}-0000-1000-8000-00805f9b34fb")

    fun isTrackr(name: String?): Boolean =
        name?.trim()?.lowercase()?.startsWith(NAME_PREFIX) == true

    fun isKnownTagName(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        return KNOWN_TAG_PREFIXES.any { n.startsWith(it) }
    }
}

/** Why a device earned a place in the list. Drives both UI and re-filtering. */
enum class MatchReason {
    /** Advertised Immediate Alert -- we know it can be rung. */
    ALERT_SERVICE,

    /** Name looks like a known tag family. Probably ringable. */
    KNOWN_NAME,

    /** Only visible because the user asked to see everything. */
    SHOW_ALL,
}

/** One observation of a device from a BLE advertisement. */
data class Sighting(
    val address: String,
    val name: String,
    val rssi: Int,
    val seenAt: Long = System.currentTimeMillis(),
    val matchReason: MatchReason = MatchReason.KNOWN_NAME,
) {
    /** True when we have positive evidence the device can be rung. */
    val ringable: Boolean get() = matchReason != MatchReason.SHOW_ALL

    val ageMillis: Long get() = System.currentTimeMillis() - seenAt

    /**
     * Log-distance path loss. This is a coarse hint only -- RSSI is noisy and
     * body/pocket/wall attenuation easily doubles the apparent distance.
     * Useful for hot/cold, not for a number you should trust.
     */
    val approxMeters: Double
        get() = 10.0.pow((TX_POWER_AT_1M - rssi) / (10.0 * PATH_LOSS_EXPONENT))

    /** 0f (far/absent) .. 1f (touching) -- for driving a proximity bar. */
    val closeness: Float
        get() = ((rssi + 100).coerceIn(0, 60)) / 60f

    companion object {
        const val TX_POWER_AT_1M = -59.0
        const val PATH_LOSS_EXPONENT = 2.5
    }
}
