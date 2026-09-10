package com.agilesalt.trackrfinder

import java.util.Locale
import java.util.UUID

enum class DetailField(val label: String, service: String, characteristic: String) {
    NAME("Device name", "1800", "2a00"),
    MANUFACTURER("Manufacturer", "180a", "2a29"),
    MODEL("Model", "180a", "2a24"),
    FIRMWARE("Firmware", "180a", "2a26"),
    SOFTWARE("Software", "180a", "2a28"),
    TX_POWER("Transmit power", "1804", "2a07"),
    APPEARANCE("Appearance", "1800", "2a01"),
    CONNECTION("Preferred connection timings", "1800", "2a04");

    val serviceUuid: UUID = UUID.fromString("0000$service-0000-1000-8000-00805f9b34fb")
    val characteristicUuid: UUID = UUID.fromString("0000$characteristic-0000-1000-8000-00805f9b34fb")

    fun decode(bytes: ByteArray): String? = when (this) {
        NAME, MANUFACTURER, MODEL, FIRMWARE, SOFTWARE ->
            bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                .filterNot { it.isISOControl() }.trim().takeIf { it.isNotEmpty() }
        TX_POWER -> bytes.singleOrNull()?.toInt()?.takeIf { it in -100..20 }?.let { "$it dBm" }
        APPEARANCE -> if (bytes.size == 2) "0x%04X".format(bytes.u16(0)) else null
        CONNECTION -> if (bytes.size == 8) {
            val min = bytes.u16(0)
            val max = bytes.u16(2)
            val latency = bytes.u16(4)
            val timeout = bytes.u16(6)
            if ((min != 0xffff && min !in 6..3200) ||
                (max != 0xffff && max !in 6..3200) ||
                (min != 0xffff && max != 0xffff && min > max) ||
                latency !in 0..499 || timeout !in 10..3200
            ) null else {
                fun interval(value: Int) = if (value == 0xffff) "unspecified"
                    else String.format(Locale.ROOT, "%.2f ms", value * 1.25)
                "${interval(min)} – ${interval(max)}; skip $latency intervals; timeout ${timeout * 10} ms"
            }
        } else null
    }
}

private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

data class DeviceDetails(
    val values: Map<DetailField, String> = emptyMap(),
    val canRename: Boolean = false,
    val complete: Boolean = true,
)

object HardwareName {
    // Fits a single write with the minimum ATT MTU; do not silently truncate UTF-8.
    fun error(name: String): String? = when {
        name.isBlank() -> "Enter a device name."
        name.any { it.isISOControl() } -> "The name cannot contain control characters."
        name.toByteArray(Charsets.UTF_8).size > 20 -> "Name is too long. Try a shorter name."
        else -> null
    }
}
