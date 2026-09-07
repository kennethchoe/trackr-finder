package com.agilesalt.trackrfinder

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w393dp-h851dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlertOptionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchesClearlyNameTheSoundingDeviceAndCanBothBeEnabled() {
        var phoneTests = 0
        var phone by mutableStateOf(false)
        var tracker by mutableStateOf<TrackerAlertState?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        AlertOptions(phone, { phone = !phone }, tracker, false, true, { on ->
                            tracker = TrackerAlertState("tag", TrackerAlertPhase.READY,
                                connected = true, enabled = on,
                                message = "Ready · tracker will beep if the connection is lost")
                        }, {}, onTestPhoneAlert = { phoneTests++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Phone alert").performClick().assertIsOn()
        compose.onNodeWithText("Test phone alert").performClick()
        compose.runOnIdle { assertEquals(1, phoneTests) }
        compose.onNodeWithContentDescription("Tracker alarm").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Phone alert").assertIsOn()
        compose.onNodeWithText("Use either alert, or both.").assertIsDisplayed()
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Connected to this phone.").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/alert-options.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithContentDescription("Phone alert").performClick().assertIsOff()
        compose.onNodeWithContentDescription("Tracker alarm").assertIsOn()
    }

    @Test fun failedOffShowsPendingCleanupAndRetry() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    AlertOptions(false, {}, TrackerAlertState("tag", TrackerAlertPhase.ERROR,
                        enabled = false, message = "Off not confirmed"), false, true, {}, { retries++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Tracker alarm").assertIsOff()
        compose.onNodeWithText("The tracker may still beep until Off is confirmed. Bring it nearby to finish.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
