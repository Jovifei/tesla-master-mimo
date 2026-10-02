package com.matelink.ui.screens.charges

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeDetailFallbackContractTest {
    @Test
    fun remoteDetailFailureDoesNotSynthesizeElectricalTrace() {
        val source = File(
            "src/main/java/com/matelink/ui/screens/charges/ChargeDetailViewModel.kt"
        ).readText()
        val detailFailure = source
            .substringAfter("when (detailResult) {")
            .substringAfter("is ApiResult.Error -> {")
            .substringBefore("} else {")

        assertTrue(detailFailure.contains("val localSummary = chargeSummaryDao.get(localHistoryCarId, chargeId)"))
        assertTrue(detailFailure.contains("chargePoints = emptyList()"))
        assertFalse(source.contains("synthesizeChargePoints"))
        assertFalse(Regex("pointCount\\s*=\\s*20").containsMatchIn(source))
    }
}
