package com.agilesalt.trackrfinder

import org.junit.Assert.*
import org.junit.Test

class DeviceDetailsTest {
    @Test fun txPowerIsSignedAndMalformedValuesAreUnavailable() {
        assertEquals("-20 dBm", DetailField.TX_POWER.decode(byteArrayOf(-20)))
        assertNull(DetailField.TX_POWER.decode(byteArrayOf(127)))
        assertNull(DetailField.TX_POWER.decode(byteArrayOf()))
    }

    @Test fun decodesTheConnectionParametersReadFromTheTracker() {
        assertEquals("500.00 ms – 1000.00 ms; skip 0 intervals; timeout 4000 ms",
            DetailField.CONNECTION.decode(byteArrayOf(0x90.toByte(), 1, 0x20, 3, 0, 0, 0x90.toByte(), 1)))
        assertNull(DetailField.CONNECTION.decode(byteArrayOf(1, 2)))
        assertNull(DetailField.CONNECTION.decode(ByteArray(8)))
    }

    @Test fun deviceStringsAndAppearanceAreDecodedForDisplay() {
        assertEquals("tkr", DetailField.NAME.decode("tkr\u0000".toByteArray()))
        assertNull(DetailField.MODEL.decode(byteArrayOf()))
        assertEquals("0x0240", DetailField.APPEARANCE.decode(byteArrayOf(0x40, 2)))
    }

    @Test fun nameLimitsCountUtf8BytesAndRejectEmptyOrControlCharacters() {
        assertNull(HardwareName.error("Keys"))
        assertNull(HardwareName.error("a".repeat(20)))
        assertNotNull(HardwareName.error("a".repeat(21)))
        assertNotNull(HardwareName.error("🔑".repeat(6)))
        assertNotNull(HardwareName.error("\nKeys"))
        assertNotNull(HardwareName.error("  "))
    }
}
