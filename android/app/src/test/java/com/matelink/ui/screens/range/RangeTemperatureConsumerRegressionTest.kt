package com.matelink.ui.screens.range

import com.matelink.data.api.models.CarStatusResponse
import com.matelink.data.api.models.Units
import com.matelink.data.repository.CarStatusWithUnits
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeTemperatureConsumerRegressionTest {
    private fun temperature(raw: Double, unitJson: String): Double? {
        val response = Moshi.Builder().build().adapter(CarStatusResponse::class.java).fromJson("""{
            "data":{"status":{"climate_details":{"outside_temp":$raw}},"units":$unitJson}}
        """)!!.data!!
        val input = rangeStatusInputs(CarStatusWithUnits(response.status!!, response.units ?: Units()))
        assertEquals("canonical projection must not rewrite its source receipt", raw, response.status!!.outsideTemp!!, 0.0)
        return input.temperatureC
    }

    @Test fun fahrenheitSnapshotHasCanonicalTemperatureWithoutChangingTheWireValue() {
        assertEquals(20.0, temperature(68.0, """{"unit_of_temperature":"F"}""") ?: Double.NaN, 1e-9)
    }

    @Test fun observedCelsiusZeroAndNegativeTemperatureRemainKnown() {
        assertEquals(0.0, temperature(0.0, """{"unit_of_temperature":"C"}""") ?: Double.NaN, 0.0)
        assertEquals(-5.0, temperature(-5.0, """{"unit_of_temperature":"C"}""") ?: Double.NaN, 0.0)
    }

    @Test fun missingOrUnknownTemperatureUnitCannotBeGuessedFromLengthUnit() {
        for (unit in listOf("null", "{}", """{"unit_of_length":"km"}""", """{"unit_of_temperature":"K"}""")) {
            assertNull(temperature(20.0, unit))
        }
    }
}
