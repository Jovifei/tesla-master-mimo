package com.matelink.ui.screens.readiness

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataReadinessReviewContractTest {
    @Test
    fun dashboardPublishesVehicleStateBeforeStartingReadinessFetch() {
        val source = File("src/main/java/com/matelink/ui/screens/dashboard/DashboardViewModel.kt").readText()

        val statePublication = source.indexOf("_uiState.value = DashboardUiState")
        val readinessFetch = source.indexOf("repository.getDataReadiness")

        assertTrue(statePublication >= 0)
        assertTrue(readinessFetch >= 0)
        assertTrue("vehicle state must publish before readiness can block it", statePublication < readinessFetch)
        assertTrue(source.contains("requestGeneration"))
        assertTrue(
            "stale dashboard loads must not publish either success or error state",
            Regex("if \\(generation != requestGeneration\\) return@launch").findAll(source).count() >= 3
        )
    }

    @Test
    fun readinessIntroUsesTheLoadedReadinessCarIdWithoutAPlaceholderFallback() {
        val source = File("src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt").readText()

        assertTrue(source.contains("uiState.dataReadinessCarId"))
        assertFalse(source.contains("onNavigateToReadiness(uiState.car?.carId ?: 1)"))
    }

    @Test
    fun readinessIntroHasAConstrainedScrollableBodyForLargeText() {
        val source = File("src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt").readText()
        val readinessSource = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessScreen.kt").readText()

        assertTrue(source.contains("heightIn(max ="))
        assertTrue(source.contains("verticalScroll(rememberScrollState())"))
        assertEquals(
            6,
            listOf("live_status", "location", "tpms", "drives", "charges", "battery_health")
                .count { readinessSource.contains("\"$it\"") }
        )
    }

    @Test
    fun unknownAndMissingReadinessValuesNeverReachTheUiAsRawCodes() {
        val screen = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessScreen.kt").readText()
        val presentation = File("src/main/java/com/matelink/ui/screens/readiness/ReadinessPresentation.kt").readText()

        assertFalse(screen.contains("data_readiness_status_unknown, \"not_observed\""))
        assertFalse(screen.contains("statusText(item)"))
        assertFalse(presentation.contains("data_readiness_status_unknown, item.status"))
        assertTrue(screen.contains("data_readiness_status_unavailable"))
        assertTrue(presentation.contains("data_readiness_status_unavailable"))
    }

    @Test
    fun chineseReadinessCopyUsesFullStopAndLocalizedUnavailableStatus() {
        assertEquals(
            "车辆已连接，部分数据正在准备。",
            stringValue("values-zh", "data_readiness_intro_title")
        )
        assertEquals("暂不可用", stringValue("values-zh", "data_readiness_status_unavailable"))
        assertEquals("Unavailable", stringValue("values", "data_readiness_status_unavailable"))
    }

    private fun stringValue(directory: String, name: String): String {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }
}
