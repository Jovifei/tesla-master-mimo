package com.matelink.ui.screens.stats

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class StatsRecommendationFormatContractTest {
    @Test
    fun recommendationFormatsAcceptTheirComposeArguments() {
        listOf("values", "values-zh").forEach { directory ->
            String.format(Locale.ROOT, stringValue(directory, "stats_recommendation_standby_evidence"), 260.0, 200.0, 5, 20.0, 30, 80)
            String.format(Locale.ROOT, stringValue(directory, "stats_recommendation_efficiency_evidence"), 220.0, 180.0, 10, 240.0, 60, 80)
            String.format(Locale.ROOT, stringValue(directory, "stats_recommendation_charge_loss_evidence"), 16.5, 12.0, 5, 70.0, 30, 80)
            String.format(Locale.ROOT, stringValue(directory, "stats_recommendation_impact"), 1.2, 2.4)
            String.format(Locale.ROOT, stringValue(directory, "stats_recommendation_action"), "action")
        }
    }

    private fun stringValue(resourceDirectory: String, name: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$resourceDirectory/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }
}
