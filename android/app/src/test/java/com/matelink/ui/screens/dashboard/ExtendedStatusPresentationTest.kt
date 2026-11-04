package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CarStatusDetails
import com.matelink.data.api.models.CarVersions
import com.matelink.data.api.models.ChargingDetails
import com.matelink.data.api.models.ClimateDetails
import com.matelink.data.api.models.MediaInfo
import com.matelink.data.api.models.SoftwareUpdate
import com.matelink.data.api.models.SpeedLimitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedStatusPresentationTest {

    @Test
    fun mediaPlaybackJoinsTitleAndArtist() {
        val status = CarStatus(
            carStatus = CarStatusDetails(
                mediaInfo = MediaInfo(mediaState = "Playing", title = "Song", artist = "Singer")
            )
        )
        assertEquals("Song — Singer", mediaPlayback(status)?.displayText)
    }

    @Test
    fun mediaPlaybackAbsentWithoutTitle() {
        assertNull(mediaPlayback(CarStatus()))
        assertNull(
            mediaPlayback(
                CarStatus(carStatus = CarStatusDetails(mediaInfo = MediaInfo(mediaState = "Stopped")))
            )
        )
    }

    @Test
    fun softwareUpdateOnlySurfacesActivePhases() {
        val downloading = CarStatus(
            carVersions = CarVersions(
                softwareUpdate = SoftwareUpdate(status = "downloading", downloadPercent = 42)
            )
        )
        assertEquals(
            SoftwareUpdateUi(SoftwareUpdatePhase.DOWNLOADING, 42),
            softwareUpdateUi(downloading)
        )

        val scheduled = CarStatus(
            carVersions = CarVersions(softwareUpdate = SoftwareUpdate(status = "scheduled"))
        )
        assertNull(softwareUpdateUi(scheduled))
        assertNull(softwareUpdateUi(CarStatus()))
    }

    @Test
    fun scheduledEventsRenderChargingBeforeDeparture() {
        val status = CarStatus(
            chargingDetails = ChargingDetails(
                scheduledChargingStartTime = "2026-01-01T16:00:00Z",
                scheduledDepartureTime = "2026-01-01T23:30:00Z"
            )
        )
        val events = scheduledEvents(status)
        assertEquals(
            listOf(ScheduledKind.CHARGING_START, ScheduledKind.DEPARTURE),
            events.map { it.kind }
        )
        assertTrue(events.all { it.timeText.matches(Regex("""\d{2}:\d{2}""")) })
        assertTrue(scheduledEvents(CarStatus()).isEmpty())
    }

    @Test
    fun vehicleModesRequireValetOrActiveLimit() {
        assertNull(vehicleModes(CarStatus()))
        assertNull(
            vehicleModes(
                CarStatus(
                    carStatus = CarStatusDetails(
                        valetMode = false,
                        speedLimitMode = SpeedLimitMode(active = false, currentLimitKmh = 90)
                    )
                )
            )
        )
        val modes = vehicleModes(
            CarStatus(
                carStatus = CarStatusDetails(
                    speedLimitMode = SpeedLimitMode(active = true, currentLimitKmh = 113)
                )
            )
        )
        assertEquals(VehicleModes(valetOn = false, speedLimitKmh = 113), modes)
    }

    @Test
    fun climateTargetsNeedAtLeastOneSide() {
        assertNull(climateTargets(CarStatus()))
        val targets = climateTargets(
            CarStatus(climateDetails = ClimateDetails(driverTempSetting = 22.5))
        )
        assertEquals(ClimateTargets(driver = 22.5, passenger = null), targets)
    }
}
