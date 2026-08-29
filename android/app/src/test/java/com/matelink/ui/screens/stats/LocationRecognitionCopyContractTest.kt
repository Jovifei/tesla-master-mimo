package com.matelink.ui.screens.stats

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRecognitionCopyContractTest {
    @Test
    fun chineseLocationRecognitionCopyExplainsProcessedAndPendingPlaces() {
        assertEquals("地点识别", stringValue("values-zh", "geocode_progress_title"))
        assertEquals(
            "已识别 %1\$d 个历史地点，待处理 %2\$d 个",
            stringValue("values-zh", "geocode_progress_status")
        )
    }

    @Test
    fun progressCardPassesPendingCountInsteadOfTheTotalCount() {
        val source = File("src/main/java/com/matelink/ui/screens/stats/StatsScreen.kt").readText()

        assertTrue(source.contains("progress.total - progress.processed"))
    }

    private fun stringValue(directory: String, name: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }
}
