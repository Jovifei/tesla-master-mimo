package com.matelink.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.local.TirePosition
import com.matelink.domain.model.*
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Real Compose consumers on the isolated emulator. All values and images here are synthetic/public. */
@RunWith(AndroidJUnit4::class)
class InteractionVisualAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wheelAndEndpointSelectionUseActualSemanticsAndNeverWriteData() {
        val now = 1_800_000_000_000L
        val selected = mutableStateOf<TirePosition?>(null)
        val readings = mutableStateOf(mapOf(
            TirePosition.FL to visualWheelReading(0.0, now, now, true),
            TirePosition.FR to visualWheelReading(2.6, now-3_600_000, now, true)))
        compose.setContent { MateLinkTheme {
            Column(Modifier.width(320.dp)) {
                TpmsVehicleVisual(readings.value, selected.value, { selected.value = it })
                RouteInteractionVisual("Synthetic A", "Synthetic B")
            }
        } }
        compose.onNodeWithText("0.00 bar").assertIsDisplayed()
        compose.onNodeWithTag("interaction-wheel-FL").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(TirePosition.FL, selected.value) }
        compose.onNodeWithTag("interaction-wheel-FL").performClick().assertIsNotSelected()
        compose.onNodeWithTag("interaction-route-1").performClick().assertIsSelected()
        compose.runOnIdle { readings.value = emptyMap() }
        compose.onNodeWithText("0.00 bar").assertDoesNotExist()
        compose.onNodeWithText("2.60 bar").assertDoesNotExist()
    }

    @Test fun narrowLargeTextWheelControlsNeverOverlapVehicleOrEachOther() {
        val scale = mutableStateOf(1f)
        compose.setContent { MateLinkTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                Column(Modifier.width(320.dp).padding(16.dp)) {
                    TpmsVehicleVisual(emptyMap(), null, {})
                }
            }
        } }
        for (fontScale in listOf(1f, 1.8f)) {
            compose.runOnIdle { scale.value = fontScale }
            compose.waitForIdle()
            val car = compose.onNodeWithTag("interaction-tpms-car").fetchSemanticsNode().boundsInRoot
            fun wheel(name: String) = compose.onNodeWithTag("interaction-wheel-$name")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val fl = wheel("FL"); val fr = wheel("FR"); val rl = wheel("RL"); val rr = wheel("RR")
            assertTrue("left controls overlap car at scale=$fontScale", fl.right <= car.left && rl.right <= car.left)
            assertTrue("right controls overlap car at scale=$fontScale", fr.left >= car.right && rr.left >= car.right)
            assertTrue("front/rear controls overlap at scale=$fontScale", fl.bottom < rl.top && fr.bottom < rr.top)
        }
    }

    @Test fun allVisualGroupsRenderAtPhoneWidthsAndInBothThemes() {
        val scene = mutableStateOf(0); val dark = mutableStateOf(false); val width = mutableStateOf(320)
        val now = 1_800_000_000_000L
        val readings = mapOf(
            TirePosition.FL to visualWheelReading(2.4, now, now, true),
            TirePosition.FR to visualWheelReading(0.0, now, now, true, true),
            TirePosition.RL to visualWheelReading(2.5, now-3_600_000, now, true))
        compose.setContent { MateLinkTheme(darkTheme = dark.value) {
            Column(Modifier.width(width.value.dp).heightIn(min = 360.dp)
                .background(MaterialTheme.colorScheme.background).padding(16.dp).testTag("visual-fixture"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("MateLink · synthetic visual fixture", color = MaterialTheme.colorScheme.onBackground)
                when (scene.value) {
                    0 -> ConnectionVisual(ConnectionVisualPhase.IDLE)
                    1 -> ConnectionVisual(ConnectionVisualPhase.CONNECTING)
                    2 -> ConnectionVisual(ConnectionVisualPhase.ACTION_REQUIRED, vehicle = true)
                    3 -> ConnectionVisual(ConnectionVisualPhase.CONNECTED, vehicle = true)
                    4 -> ConnectionVisual(ConnectionVisualPhase.UNAVAILABLE)
                    5 -> TpmsVehicleVisual(readings, null, {})
                    6 -> RouteInteractionVisual("Synthetic origin", "Synthetic destination")
                    7 -> VehicleOpeningsVisual(true, null, false, true)
                    8 -> { EmptyHistoryVisual(false); EmptyHistoryVisual(true) }
                    9 -> { RecordedChargeRail(true); RecordedChargeRail(false)
                        Text("Recorded intervals only · missing end stays unknown", color = MaterialTheme.colorScheme.onBackground) }
                    else -> MateLinkLoadingMark()
                }
            }
        } }
        for (night in listOf(false, true)) for (index in 0..10) {
            compose.runOnIdle { dark.value = night; scene.value = index; width.value = if (night) 412 else 320 }
            compose.waitForIdle()
            capture("matelink_visual_${if (night) "dark" else "light"}_$index")
        }
    }

    private fun capture(name: String) {
        check(Regex("matelink_visual_(light|dark)_\\d+").matches(name))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fd = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        val emulator = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
        check(emulator == "1") { "Only an isolated emulator may export synthetic fixtures" }
        val bitmap = compose.onNodeWithTag("visual-fixture").captureToImage().asAndroidBitmap()
        val output = ByteArrayOutputStream(); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        val data = output.toByteArray(); check(data.size > 1000)
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val dir = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val image = File(dir, "$name.png").apply { writeBytes(data) }
        val proof = File(dir, "$name.proof").apply { writeText("$digest ${bitmap.width} ${bitmap.height} synthetic") }
        for (file in listOf(image, proof)) {
            val copy = instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/${file.name}")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        }
    }
}
