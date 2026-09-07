package com.agilesalt.trackrfinder

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class DeviceCardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun readyCard() = review("ready", TrackerAlertState("tag", TrackerAlertPhase.READY,
        connected = true, enabled = true, message = "Ready · tracker will beep if the connection is lost"))

    @Test fun offCard() = review("off", null)

    @Test fun pendingOffCard() = review("pending-off", TrackerAlertState("tag", TrackerAlertPhase.ERROR,
        enabled = false, message = "The tracker did not confirm the alarm setting. Tap Retry."))

    @Test @Config(qualifiers = "w320dp-h740dp")
    fun narrowCardWithLargeText() = review("large-text", TrackerAlertState("tag", TrackerAlertPhase.READY,
        connected = true, enabled = true, message = "Ready · tracker will beep if the connection is lost"), 1.5f)

    private fun review(name: String, state: TrackerAlertState?, fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 20.dp)) {
                            Header("TrackR Finder", if (state?.connected == true) "1 connected · scanning" else "Scanning · 1 nearby", false, 1)
                            Spacer(Modifier.height(14.dp))
                            FilterChip(false, {}, label = { Text("Show all Bluetooth devices") })
                            Spacer(Modifier.height(14.dp))
                            LazyColumn(Modifier.weight(1f)) {
                                item {
                                    DeviceCard(Sighting("CE:D5:27:82:67:DC", "tkr", -66, seenAt = 10000L),
                                        "Everyday keys", null, true, true, false, false, false,
                                        state, false, {}, {}, {}, 10000L, {}, {}, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Ring").assertIsDisplayed()
        capture("$name-top")
        compose.onNodeWithText("Device details").performScrollTo().assertIsDisplayed()
        val nicknameLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Nickname").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(nicknameLayout) }
        val layout = nicknameLayout.single()
        assertEquals("Full nickname label must fit", "Nickname".length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        // Text metrics can round up against an integer layout width by a pixel.
        assertTrue("Nickname must fit its measured width", layout.getLineRight(0) <= layout.size.width + 1f)
        assertFalse("Nickname must fit its measured height", layout.didOverflowHeight)
        if (state != null) {
            compose.onNodeWithText("Turn off the tracker alarm to check battery or device details.")
                .performScrollTo().assertIsDisplayed()
        }
        capture("$name-bottom")
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/ui/review/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
