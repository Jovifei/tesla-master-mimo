package com.matelink.ui.screens.battery

import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.DataReadinessItem
import com.matelink.data.repository.ApiResult
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BatteryHealthAvailabilityTest {
    @Test
    fun notFoundHealthEndpointIsUnavailableRatherThanUnsupported() {
        assertEquals(
            BatteryHealthAvailability.UNAVAILABLE,
            classifyBatteryHealth(ApiResult.Error("request failed", 404), null)
        )
    }

    @Test
    fun readinessCollectingIsTypedAsCollectingInsteadOfRawTransportText() {
        val readiness = DataReadinessItem(
            key = "battery_health",
            status = "collecting",
            source = "fleet_api"
        )

        assertEquals(
            BatteryHealthAvailability.COLLECTING,
            classifyBatteryHealth(ApiResult.Error("No battery health data returned"), readiness)
        )
    }

    @Test
    fun emptyHealthPayloadIsUnavailableUntilCapacityIsMeasured() {
        assertEquals(
            BatteryHealthAvailability.UNAVAILABLE,
            classifyBatteryHealth(ApiResult.Success(BatteryHealth()), null)
        )
    }

    @Test
    fun measuredCapacityHealthIsAvailable() {
        assertEquals(
            BatteryHealthAvailability.AVAILABLE,
            classifyBatteryHealth(ApiResult.Success(BatteryHealth(maxCapacity = 80.0, currentCapacity = 75.0)), null)
        )
    }

    @Test
    fun unsupportedMessageDescribesMissingMeasuredCapacityRatherThanMissingRangeTrend() {
        fun message(directory: String): String {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$directory/strings.xml"))
            val strings = document.getElementsByTagName("string")
            return (0 until strings.length).map { strings.item(it) }
                .first { it.attributes.getNamedItem("name").nodeValue == "battery_health_unsupported" }
                .textContent
        }
        assertEquals("当前数据源未提供实测电池容量。", message("values-zh"))
        assertEquals("Measured battery capacity is not provided by this data source.", message("values"))
    }
}
