package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.OpenMeteoApi
import com.matelink.data.api.OpenMeteoWeatherResponse
import com.matelink.data.api.HourlyWeatherData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.sync.toSyncSummary
import com.matelink.ui.screens.trips.TripRoutePoint
import com.matelink.ui.screens.trips.TripRouteSegment
import java.time.LocalDateTime
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import retrofit2.http.GET

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28],manifest=Config.NONE)
class WeatherTimezoneEvidenceTest {
    private class Api : OpenMeteoApi {
        val days = mutableListOf<String>()
        override suspend fun getHistoricalWeather(latitude:Double,longitude:Double,startDate:String,endDate:String):Response<OpenMeteoWeatherResponse> {
            days += startDate
            return Response.success(OpenMeteoWeatherResponse(HourlyWeatherData((0..23).map {
                "${startDate}T%02d:00".format(it) }, (0..23).map { it.toDouble() },List(24){0})))
        }
    }
    private val at = "2026-10-27T20:10:00Z"

    @Test fun shanghaiPhoneQueriesTheObservedUtcDayAndHourForDriveWeather() = runTest {
        val previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val api=Api()
            val value=WeatherRepository(api).getWeatherAlongDrive(listOf(DrivePosition(date=at,latitude=31.0,longitude=121.0)),1.0).first()
            assertTrue(api.days.all { it=="2026-10-27" })
            assertEquals(20.0,value.temperatureCelsius,0.0)
        } finally {TimeZone.setDefault(previous)}
    }

    @Test fun shanghaiTripUsesUtcProviderHourWhileDisplayingTheObservedLocalTime() = runTest {
        val previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val api=Api()
            val start="2026-10-27T20:00:00Z";val end="2026-10-27T21:00:00Z"
            val drive=DriveData(7,startDate=start,endDate=end,durationMin=60,source="teslamate_archive",qualityState="observed").toSyncSummary(-71)!!
            val segment=TripRouteSegment(listOf(TripRoutePoint(31.0,121.0,at)),7,start,end,"synthetic")
            val value=WeatherRepository(api).getWeatherAlongTrip(listOf(drive),listOf(segment)).single()
            assertTrue(api.days.all { it=="2026-10-27" })
            assertEquals(20.0,value.temperatureCelsius,0.0)
            assertEquals(LocalDateTime.of(2026,10,28,4,10),value.timestamp)
            assertEquals(1,value.clockContractVersion)
        } finally {TimeZone.setDefault(previous)}
    }

    @Test fun utcPhoneControlUsesTheSameActualProviderHour() = runTest {
        val previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val api=Api()
            val value=WeatherRepository(api).getWeatherAlongDrive(listOf(DrivePosition(date=at,latitude=31.0,longitude=121.0)),1.0).first()
            assertEquals(listOf("2026-10-27"),api.days)
            assertEquals(20.0,value.temperatureCelsius,0.0)
        } finally {TimeZone.setDefault(previous)}
    }

    @Test fun actualRetrofitContractExplicitlyRequestsGmtAndCelsius() {
        val path=OpenMeteoApi::class.java.methods.single { it.name=="getHistoricalWeather" }.getAnnotation(GET::class.java).value
        assertTrue(path.contains("timezone=GMT"))
        assertTrue(path.contains("temperature_unit=celsius"))
    }
}
