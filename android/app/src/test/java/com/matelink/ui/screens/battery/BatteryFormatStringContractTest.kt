package com.matelink.ui.screens.battery

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryFormatStringContractTest {

    @Test
    fun usablePercentageLabelFormatsWithTheValuePassedByBatteryStatusCard() {
        listOf("values", "values-zh").forEach { resourceDirectory ->
            val value = stringValue(resourceDirectory, "usable_percent")

            String.format(Locale.ROOT, value, 42)
        }
    }

    @Test
    fun syncProgressFormatsTheActualPercentInEverySupportedLocale() {
        listOf("values", "values-zh").forEach { resourceDirectory ->
            val value = stringValue(resourceDirectory, "stats_sync_percent")

            assertEquals("42%", String.format(Locale.ROOT, value, 42))
        }
    }

    @Test
    fun rangeAtFullLabelUsesOneLiteralPercentWithoutFormattingArguments() {
        assertEquals("Range at 100%", stringValue("values", "range_at_100"))
        assertEquals("100% 时续航", stringValue("values-zh", "range_at_100"))
    }

    private fun stringValue(resourceDirectory: String, name: String): String {
        val resourceFile = File("src/main/res/$resourceDirectory/strings.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resourceFile)
        val strings = document.getElementsByTagName("string")

        for (index in 0 until strings.length) {
            val element = strings.item(index) as org.w3c.dom.Element
            if (element.getAttribute("name") == name) return element.textContent
        }

        error("Missing string '$name' in $resourceDirectory")
    }
}
