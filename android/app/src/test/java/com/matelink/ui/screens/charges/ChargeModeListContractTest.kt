package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.EnergyContract
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeModeListContractTest {
    @Test fun unprovenFleetModeCannotBeReclassifiedByLegacyDaoIndex() {
        val unknown = ChargeData(chargeId = 8, chargeType = "ac",
            energyContract = EnergyContract())
        assertEquals(ChargeType.UNKNOWN, historyChargeType(unknown,
            dcIds = setOf(8), processedIds = setOf(8)))
    }

    @Test fun verifiedFleetDcIsRecognizedWithoutOldAggregateIndex() {
        val certified = ChargeData(chargeId = 9, chargeType = "dc",
            energyContract = EnergyContract(chargeMode = "dc",
                chargeModeEvidence = "observed_boundary_modes_no_conflict"))
        assertEquals(ChargeType.DC, historyChargeType(certified,
            dcIds = emptySet(), processedIds = emptySet()))
    }

    @Test fun contradictoryDeclaredModeStaysUnknownRatherThanFalseAc() {
        val mixed = ChargeData(chargeId = 10, chargeType = "dc",
            energyContract = EnergyContract(chargeMode = "ac",
                chargeModeEvidence = "observed_boundary_modes_no_conflict"))
        assertEquals(ChargeType.UNKNOWN, historyChargeType(mixed,
            dcIds = emptySet(), processedIds = emptySet()))
    }

    @Test fun legacyTypeRequiresKnownAggregateEvidence() {
        val old = ChargeData(chargeId = 11)
        assertEquals(ChargeType.UNKNOWN, historyChargeType(old,
            dcIds = emptySet(), processedIds = emptySet()))
        assertEquals(ChargeType.AC, historyChargeType(old,
            dcIds = emptySet(), processedIds = setOf(11)))
        assertEquals(ChargeType.DC, historyChargeType(old,
            dcIds = setOf(11), processedIds = setOf(11)))
    }
}
