package com.matelink.ui.screens.updates

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class SoftwareFormatStringContractTest {

    @Test
    fun intervalLabelFormatsTheIntegerProducedByOverviewCard() {
        listOf("values", "values-zh").forEach { resourceDirectory ->
            String.format(Locale.ROOT, stringValue(resourceDirectory, "format_interval_days"), 7)
        }
    }

    private fun stringValue(resourceDirectory: String, name: String): String {
        val strings = File("src/main/res/$resourceDirectory/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings)
        val entries = document.getElementsByTagName("string")
        for (index in 0 until entries.length) {
            val entry = entries.item(index)
            if (entry.attributes.getNamedItem("name")?.nodeValue == name) return entry.textContent
        }
        error("Missing string $name in $resourceDirectory")
    }
}
