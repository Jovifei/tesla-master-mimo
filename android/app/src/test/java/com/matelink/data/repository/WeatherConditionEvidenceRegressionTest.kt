package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.OpenMeteoApi
import com.matelink.data.api.OpenMeteoWeatherResponse
import com.matelink.data.api.HourlyWeatherData
import com.matelink.data.api.models.DrivePosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class WeatherConditionEvidenceRegressionTest {
    private fun source(codes: List<Int>?) = object : OpenMeteoApi {
        override suspend fun getHistoricalWeather(latitude: Double, longitude: Double, startDate: String,
            endDate: String) = Response.success(OpenMeteoWeatherResponse(HourlyWeatherData(
                (0..23).map { "2026-10-27T%02d:00".format(it) }, List(24) { 20.0 }, codes)))
    }

    @Test fun missingWeatherCodeDoesNotManufactureClearSkyWhileTemperatureSurvives() = runTest {
        val values = WeatherRepository(source(null)).getWeatherAlongDrive(listOf(
            DrivePosition(date = "2026-10-27T10:00:00Z", latitude = 31.0, longitude = 121.0)), 1.0)
        assertTrue(values.isNotEmpty())
        assertEquals(20.0, values.first().temperatureCelsius, 0.0)
        assertNull(values.first().weatherCode)
        assertEquals("UNKNOWN", values.first().weatherCondition.name)
    }

    @Test fun observedZeroCodeRemainsClearAndUnknownCodeDoesNotInventClouds() = runTest {
        suspend fun condition(code: Int) = WeatherRepository(source(List(24) { code })).getWeatherAlongDrive(listOf(
            DrivePosition(date = "2026-10-27T10:00:00Z", latitude = 31.0, longitude = 121.0)), 1.0).first()
        assertEquals(WeatherCondition.CLEAR, condition(0).weatherCondition)
        assertEquals("UNKNOWN", condition(999).weatherCondition.name)
    }
}
