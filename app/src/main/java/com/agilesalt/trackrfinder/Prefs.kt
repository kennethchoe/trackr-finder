package com.agilesalt.trackrfinder

import android.content.Context

/** The one device we watch in the background, plus wherever we last saw it. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("trackr", Context.MODE_PRIVATE)

    var watchedAddress: String?
        get() = sp.getString("addr", null)
        set(v) = sp.edit().putString("addr", v).apply()

    var watchedName: String?
        get() = sp.getString("name", null)
        set(v) = sp.edit().putString("name", v).apply()

    /**
     * Whether alerts are armed. Separate from watchedAddress so turning alerts
     * off keeps the tag and its last known position on screen.
     */
    var watchEnabled: Boolean
        // Installations predating this key: an address meant alerts were on.
        get() = if (sp.contains(KEY_WATCH_ENABLED)) {
            sp.getBoolean(KEY_WATCH_ENABLED, false)
        } else {
            watchedAddress != null
        }
        set(v) = sp.edit().putBoolean(KEY_WATCH_ENABLED, v).apply()

    var trackerAlertAddress: String?
        get() = sp.getString("tracker_alert_addr", null)
        set(v) = sp.edit().putString("tracker_alert_addr", v).apply()

    var trackerAlertName: String?
        get() = sp.getString("tracker_alert_name", null)
        set(v) = sp.edit().putString("tracker_alert_name", v).apply()

    var trackerAlertEnabled: Boolean
        get() = sp.getBoolean("tracker_alert_enabled", false)
        set(v) { sp.edit().putBoolean("tracker_alert_enabled", v).commit() }

    /** Set before an enabling write; only cleared after a verified Off read. */
    var trackerAlertNeedsDisarm: Boolean
        get() = sp.getBoolean("tracker_alert_needs_disarm", false)
        set(v) { sp.edit().putBoolean("tracker_alert_needs_disarm", v).commit() }

    val trackerAlertNeedsService: Boolean
        get() = trackerAlertAddress != null && (trackerAlertEnabled || trackerAlertNeedsDisarm)

    fun clearTrackerAlert() = sp.edit()
        .remove("tracker_alert_addr").remove("tracker_alert_name")
        .remove("tracker_alert_enabled").remove("tracker_alert_needs_disarm").commit()

    /** Forget the tracked tag entirely, keeping nicknames and probe results. */
    fun forgetWatch() = sp.edit()
        .remove("addr").remove("name").remove("adv_name").remove(KEY_WATCH_ENABLED)
        .remove("seen_at").remove("lat").remove("lon").remove("location_at")
        .apply()

    /**
     * The tag's own advertised name, kept alongside the nickname so a card
     * rendered from stored state looks identical to a live one.
     */
    var watchedAdvertisedName: String?
        get() = sp.getString("adv_name", null)
        set(v) = sp.edit().putString("adv_name", v).apply()

    var lastSeenAt: Long
        get() = sp.getLong("seen_at", 0L)
        set(v) = sp.edit().putLong("seen_at", v).apply()

    /** NaN when we have never recorded a fix. */
    var lastLat: Double
        get() = Double.fromBits(sp.getLong("lat", Double.NaN.toRawBits()))
        set(v) = sp.edit().putLong("lat", v.toRawBits()).apply()

    var lastLon: Double
        get() = Double.fromBits(sp.getLong("lon", Double.NaN.toRawBits()))
        set(v) = sp.edit().putLong("lon", v.toRawBits()).apply()

    // Old installations have no fix timestamp, so their unverified coordinates
    // must not be presented as a position belonging to a new sighting.
    val hasLocation: Boolean
        get() = lastLat.isFinite() && lastLon.isFinite() &&
            LastSeenLocation.matchesSighting(sp.getLong("location_at", 0L), lastSeenAt)

    fun saveLocation(latitude: Double, longitude: Double, recordedAt: Long) {
        sp.edit()
            .putLong("lat", latitude.toRawBits())
            .putLong("lon", longitude.toRawBits())
            .putLong("location_at", recordedAt)
            .apply()
    }

    fun clearLocation() {
        sp.edit().remove("lat").remove("lon").remove("location_at").apply()
    }

    /** Persisted so it survives rotation, theme changes and process death. */
    var showAll: Boolean
        get() = sp.getBoolean("show_all", false)
        set(v) = sp.edit().putBoolean("show_all", v).apply()

    /** A local label keyed by MAC; separate from writing the device's GAP name. */
    fun nickname(address: String): String? = sp.getString(nickKey(address), null)

    fun setNickname(address: String, nick: String?) {
        val clean = nick?.trim().orEmpty()
        sp.edit().apply {
            if (clean.isEmpty()) remove(nickKey(address)) else putString(nickKey(address), clean)
        }.apply()
    }

    /** All nicknames, so the UI can seed itself in one read. */
    fun allNicknames(): Map<String, String> = sp.all
        .filterKeys { it.startsWith(NICK_PREFIX) }
        .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(NICK_PREFIX) to it } }
        .toMap()

    /** What to show for a device: nickname if set, else its advertised name. */
    fun label(address: String, advertised: String?): String =
        nickname(address) ?: advertised ?: address

    /**
     * From service discovery: true = has Immediate Alert, false = does not,
     * null = never probed. Advertisements cannot answer this.
     */
    fun ringSupport(address: String): Boolean? =
        if (sp.contains(ringKey(address))) sp.getBoolean(ringKey(address), false) else null

    fun setRingSupport(address: String, supported: Boolean) =
        sp.edit().putBoolean(ringKey(address), supported).apply()

    fun allRingSupport(): Map<String, Boolean> = sp.all
        .filterKeys { it.startsWith(RING_PREFIX) }
        .mapNotNull { (k, v) -> (v as? Boolean)?.let { k.removePrefix(RING_PREFIX) to it } }
        .toMap()

    /** Remember before writing a name, including writes whose response is lost. */
    fun rememberDevice(address: String) {
        val addresses = sp.getStringSet("known_devices", emptySet()).orEmpty() + address
        sp.edit().putStringSet("known_devices", addresses).apply()
    }

    fun isRememberedDevice(address: String): Boolean =
        address == watchedAddress || address == trackerAlertAddress || ringSupport(address) == true ||
            sp.getStringSet("known_devices", emptySet()).orEmpty().contains(address)

    private fun ringKey(address: String) = "$RING_PREFIX$address"

    private fun nickKey(address: String) = "$NICK_PREFIX$address"

    fun clear() = sp.edit().clear().apply()

    private companion object {
        const val NICK_PREFIX = "nick_"
        const val RING_PREFIX = "ring_"
        const val KEY_WATCH_ENABLED = "watch_enabled"
    }
}
