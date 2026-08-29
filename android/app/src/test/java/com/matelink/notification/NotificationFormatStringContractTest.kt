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
            assertEquals(directory, 3, placeholderCount(strings.getValue("tpms_custom_low_body")))
            assertEquals(directory, 3, placeholderCount(strings.getValue("tpms_custom_high_body")))
            assertEquals(directory, 4, placeholderCount(strings.getValue("trip_notification_body")))
            val expectedCustomPrefix = if (directory == "values") "App custom reminder" else "App 自定义提醒"
            assertTrue(directory, strings.getValue("tpms_custom_low_body").contains(expectedCustomPrefix))
            assertTrue(directory, strings.getValue("tpms_custom_high_body").contains(expectedCustomPrefix))
            assertEquals(directory, 1, placeholderCount(strings.getValue("charging_notification_dc_finished_title")))
            assertEquals(directory, 1, placeholderCount(strings.getValue("sentry_notification_title")))
            assertEquals(directory, 0, placeholderCount(strings.getValue("charging_notification_title")))
        }
    }

    @Test
    fun unformattedPercentageCopyIsExplicitlyNonFormatting() {
        listOf("values", "values-zh").forEach { directory ->
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

    @Test
    fun placeholderCountHandlesPositionalPrecisionAndEscapedPercent() {
        assertEquals(3, placeholderCount("%1\u0024s %2\u0024.2f %3\u0024d %%"))
        assertEquals(0, placeholderCount("100%% complete"))
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

    private fun placeholderCount(value: String): Int {
        var index = 0
        var count = 0
        while (index < value.length) {
            if (value[index] != '%') {
                index++
                continue
            }
            if (index + 1 < value.length && value[index + 1] == '%') {
                index += 2
                continue
            }

            val match = javaFormatConversion.find(value, index)
            if (match?.range?.first == index) {
                if (match.value.last().lowercaseChar() != 'n') count++
                index = match.range.last + 1
            } else {
                index++
            }
        }
        return count
    }

    private companion object {
        val javaFormatConversion = Regex(
            "%(?!%)(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[a-zA-Z])"
        )
    }
}
