package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.OpenMeteoApi
import com.matelink.data.api.OpenMeteoWeatherResponse
import com.matelink.data.api.HourlyWeatherData
import com.matelink.data.api.models.DriveData
import com.matelink.data.sync.toSyncSummary
import com.matelink.ui.screens.trips.TripRoutePoint
import com.matelink.ui.screens.trips.TripRouteSegment
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[28], manifest=Config.NONE)
class TripWeatherObservedTimeTest {
    private class Api : OpenMeteoApi {
        var calls = 0
        override suspend fun getHistoricalWeather(latitude: Double, longitude: Double, startDate: String,
            endDate: String): Response<OpenMeteoWeatherResponse> {
            calls++
            return Response.success(OpenMeteoWeatherResponse(HourlyWeatherData(
                (0..23).map { "2026-10-27T%02d:00".format(it) }, (0..23).map { it * 10.0 }, List(24) { 0 })))
        }
    }
    private val start = "2026-10-27T00:00:00Z"
    private val end = "2026-10-27T02:00:00Z"
    private fun drive() = DriveData(7,startDate=start,endDate=end,durationMin=120,
        source="teslamate_archive",qualityState="observed").toSyncSummary(-71)!!
    private fun segment() = TripRouteSegment(listOf(TripRoutePoint(31.0,121.0,start),
        TripRoutePoint(31.1,121.1,"2026-10-27T00:10:00Z"),TripRoutePoint(31.2,121.2,end)),7,start,end,"synthetic-receipt")

    @Test fun middleObservationAtTenMinutesCannotBecomeAnInterpolatedOneHourWeatherSample() = runTest {
        val previous = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val values = WeatherRepository(Api()).getWeatherAlongTrip(listOf(drive()),listOf(segment()))
            assertEquals(3,values.size)
            assertTrue(values.all { it.clockContractVersion==1 })
            assertEquals(0.0,values[1].temperatureCelsius,0.0)
            assertEquals(java.time.LocalDateTime.of(2026,10,27,0,10),values[1].timestamp)
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun missingPointTimesNeverGenerateUniformTimesFromTheDriveDuration() = runTest {
        val api = Api()
        val values = WeatherRepository(api).getWeatherAlongTrip(listOf(drive()),
            listOf(segment().copy(points=segment().points.map { it.copy(observedAt=null) })))
        assertTrue(values.isEmpty())
        assertEquals(0,api.calls)
    }

    @Test fun foreignDriveSlotOrWindowCannotBeUsedForThisDrivesWeather() = runTest {
        val api = Api()
        for (foreign in listOf(segment().copy(driveId=8),segment().copy(startDate="2026-10-26T00:00:00Z"))) {
            assertTrue(WeatherRepository(api).getWeatherAlongTrip(listOf(drive()),listOf(foreign)).isEmpty())
        }
        assertEquals(0,api.calls)
    }
}
