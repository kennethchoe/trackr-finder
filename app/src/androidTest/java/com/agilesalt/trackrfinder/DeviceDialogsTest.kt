package com.agilesalt.trackrfinder

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detailsShowsValuesAndAllowsHardwareRename() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DeviceDetailsDialog("Keys", "AA:BB:CC:DD:EE:FF", DeviceDetails(mapOf(
                    DetailField.NAME to "tkr", DetailField.MANUFACTURER to "Example manufacturer",
                    DetailField.MODEL to "Tracker", DetailField.FIRMWARE to "1.2.3",
                    DetailField.SOFTWARE to "1.0", DetailField.TX_POWER to "-20 dBm",
                    DetailField.APPEARANCE to "0x0240",
                    DetailField.CONNECTION to "500.00 ms – 1000.00 ms; skip 0 intervals; timeout 4000 ms",
                ), canRename = true), false, null, {}, {}, {})
            }
        }
        compose.onNodeWithText("Device details").assertIsDisplayed()
        compose.onNodeWithText("tkr").assertIsDisplayed()
        capture("device-details")
        compose.onNodeWithText("Rename device").performScrollTo().assertIsEnabled()
    }

    @Test fun hardwareRenameExplainsRejectionAndDisablesInvalidNames() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HardwareRenameDialog("tkr", false,
                    "Tracker rejected the name change. Use a phone nickname if hardware renaming is not allowed.", {}, {})
            }
        }
        compose.onNodeWithText("Write name").assertIsNotEnabled()
        compose.onNodeWithText("Device name").performTextReplacement("Keys")
        compose.onNodeWithText("Write name").assertIsEnabled()
        capture("hardware-rename")
        compose.onNodeWithText("Device name").performTextReplacement("a".repeat(21))
        compose.onNodeWithText("Write name").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
