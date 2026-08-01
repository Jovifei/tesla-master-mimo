package com.matelink.notification

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFormatStringContractTest {
    @Test
    fun notificationResourcesMatchTheirCallArguments() {
        listOf("values", "values-zh").forEach { directory ->
            val strings = loadStrings(directory)
            assertEquals(directory, 2, placeholderCount(strings.getValue("tpms_notification_body")))
            assertEquals(directory, 1, placeholderCount(strings.getValue("tpms_notification_cleared")))
            assertEquals(directory, 1, placeholderCount(strings.getValue("charging_notification_dc_finished_title")))
            assertEquals(directory, 1, placeholderCount(strings.getValue("sentry_notification_title")))
            assertEquals(directory, 0, placeholderCount(strings.getValue("charging_notification_title")))
        }
    }

    @Test
    fun unformattedPercentageCopyIsExplicitlyNonFormatting() {
        listOf("values", "values-de", "values-fr", "values-ja", "values-zh").forEach { directory ->
            val file = File("src/main/res/$directory/strings.xml")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            val node = (0 until nodes.length)
                .map { nodes.item(it) }
                .first { it.attributes.getNamedItem("name")?.nodeValue == "high_soc_warning" }
            assertEquals(directory, "false", node.attributes.getNamedItem("formatted")?.nodeValue)
            assertTrue(directory, node.textContent.contains('%'))
        }
    }

    private fun loadStrings(directory: String): Map<String, String> {
        val file = File("src/main/res/$directory/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun placeholderCount(value: String): Int =
        Regex("%(?:\\d+\\$)?s").findAll(value).count()
}
