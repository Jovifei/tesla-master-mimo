package com.matelink.data.export

import android.app.Application
import android.content.ContextWrapper
import com.matelink.data.local.entity.DriveSummary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DataExporterContractTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun drive(energy: Double?) = DriveSummary(
        driveId = 1, carId = -7, startDate = "2026-10-14T10:00:00Z",
        endDate = "2026-10-14T11:00:00Z", durationMin = 60,
        startAddress = "=UNTRUSTED()", endAddress = "B", distance = 10.0,
        speedMax = 100, speedAvg = 80, powerMax = 30, powerMin = -10,
        startBatteryLevel = 80, endBatteryLevel = 50, outsideTempAvg = null,
        insideTempAvg = null, energyConsumed = energy, efficiency = energy?.times(100))

    private fun exporter(directory: File) = DataExporter(object : ContextWrapper(
        RuntimeEnvironment.getApplication()) {
        override fun getCacheDir(): File = directory
    })

    private fun write(format: ExportFormat, value: DriveSummary, file: File) {
        val method = DataExporter::class.java.getDeclaredMethod(
            if (format == ExportFormat.CSV) "exportCsv" else "exportJson",
            File::class.java, List::class.java, List::class.java, ExportDataType::class.java)
        method.isAccessible = true
        method.invoke(exporter(file.parentFile!!), file, listOf(value), emptyList<Any>(),
            ExportDataType.DRIVES)
    }

    @Test fun csvKeepsObservedNegativeEnergyAsANumber() {
        val file = temporary.newFile("negative.csv")
        write(ExportFormat.CSV, drive(-2.0), file)
        val row = file.readLines().last().split(',')
        assertEquals("-2.0", row[4])
        assertEquals("-200.0", row[5])
    }

    @Test fun jsonRetainsExplicitUnknownEnergyAndEfficiencyKeys() {
        val file = temporary.newFile("unknown.json")
        write(ExportFormat.JSON, drive(null), file)
        val row = JSONObject(file.readText()).getJSONArray("drives").getJSONObject(0)
        assertTrue(row.has("energyKwh"))
        assertTrue(row.isNull("energyKwh"))
        assertTrue(row.has("efficiencyWhKm"))
        assertTrue(row.isNull("efficiencyWhKm"))
    }

    @Test fun exportsInTheSameClockTickNeverRewriteAnEarlierOutput() = runBlocking {
        val directory = temporary.newFolder("isolated-export")
        val exporter = exporter(directory)
        // Config.NONE deliberately has no FileProvider registration. The real
        // exporter writes before requesting its URI, so inspect only those files.
        repeat(3) { index ->
            runCatching { exporter.export(listOf(drive(index.toDouble())), emptyList(),
                ExportFormat.CSV, ExportDataType.DRIVES) }
        }
        val outputs = directory.listFiles().orEmpty().filter { it.extension == "csv" }
        assertEquals(3, outputs.size)
        assertEquals(setOf("0.0", "1.0", "2.0"),
            outputs.map { it.readLines().last().split(',')[4] }.toSet())
    }
}
