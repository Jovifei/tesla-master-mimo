package com.matelink.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    JSON("json", "application/json")
}

enum class ExportDataType {
    DRIVES,
    CHARGES,
    BOTH
}

class DataExporter(private val context: Context) {

    companion object {
        fun createShareIntent(uri: android.net.Uri, format: ExportFormat): Intent {
            return Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * Export drives and/or charges to a file.
     * Returns the file URI for sharing.
     */
    suspend fun export(
        drives: List<DriveSummary>,
        charges: List<ChargeSummary>,
        format: ExportFormat,
        dataType: ExportDataType
    ): android.net.Uri = withContext(Dispatchers.IO) {
        cleanupStaleExports()
        val timestamp = System.currentTimeMillis() / 1000
        val fileName = "matelink_export_$timestamp.${format.extension}"
        val file = File(context.cacheDir, fileName)

        when (format) {
            ExportFormat.CSV -> exportCsv(file, drives, charges, dataType)
            ExportFormat.JSON -> exportJson(file, drives, charges, dataType)
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun csvEscape(value: Any?): String {
        val s = value?.toString() ?: ""
        val escaped = if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
            "\"${s.replace("\"", "\"\"")}\"" else s
        // Defang formula-prefix characters to prevent CSV injection in spreadsheet apps
        return when {
            escaped.startsWith("=") || escaped.startsWith("+") ||
            escaped.startsWith("-") || escaped.startsWith("@") -> "'$escaped"
            else -> escaped
        }
    }

    private fun exportCsv(
        file: File,
        drives: List<DriveSummary>,
        charges: List<ChargeSummary>,
        dataType: ExportDataType
    ) {
        FileWriter(file).use { writer ->
            if (dataType == ExportDataType.DRIVES || dataType == ExportDataType.BOTH) {
                writer.append("Type,Start Date,End Date,Distance (km),Energy (kWh),Efficiency (Wh/km),Max Speed (km/h),Duration (min)\n")
                drives.forEach { d ->
                    writer.append("DRIVE,${csvEscape(d.startDate)},${csvEscape(d.endDate)},${d.distance},${csvEscape(d.energyConsumed)},${csvEscape(d.efficiency)},${csvEscape(d.speedMax)},${d.durationMin}\n")
                }
            }
            if (dataType == ExportDataType.CHARGES || dataType == ExportDataType.BOTH) {
                if (dataType == ExportDataType.BOTH) writer.append("\n")
                writer.append("Type,Start Date,End Date,Energy Added (kWh),Cost,Duration (min),Start Battery %,End Battery %\n")
                charges.forEach { c ->
                    writer.append("CHARGE,${csvEscape(c.startDate)},${csvEscape(c.endDate)},${c.energyAdded},${csvEscape(c.cost)},${csvEscape(c.durationMin)},${csvEscape(c.startBatteryLevel)},${csvEscape(c.endBatteryLevel)}\n")
                }
            }
        }
    }

    private fun exportJson(
        file: File,
        drives: List<DriveSummary>,
        charges: List<ChargeSummary>,
        dataType: ExportDataType
    ) {
        val root = JSONObject()
        root.put("exportDate", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(System.currentTimeMillis()))

        if (dataType == ExportDataType.DRIVES || dataType == ExportDataType.BOTH) {
            val drivesArr = JSONArray()
            drives.forEach { d ->
                val obj = JSONObject()
                obj.put("startDate", d.startDate)
                obj.put("endDate", d.endDate ?: JSONObject.NULL)
                obj.put("distanceKm", d.distance)
                obj.put("energyKwh", d.energyConsumed)
                obj.put("efficiencyWhKm", d.efficiency)
                obj.put("maxSpeedKmh", d.speedMax ?: JSONObject.NULL)
                obj.put("durationMin", d.durationMin)
                drivesArr.put(obj)
            }
            root.put("drives", drivesArr)
        }

        if (dataType == ExportDataType.CHARGES || dataType == ExportDataType.BOTH) {
            val chargesArr = JSONArray()
            charges.forEach { c ->
                val obj = JSONObject()
                obj.put("startDate", c.startDate)
                obj.put("endDate", c.endDate ?: JSONObject.NULL)
                obj.put("energyAddedKwh", c.energyAdded)
                obj.put("cost", c.cost ?: JSONObject.NULL)
                obj.put("durationMin", c.durationMin)
                obj.put("startBatteryPercent", c.startBatteryLevel)
                obj.put("endBatteryPercent", c.endBatteryLevel)
                chargesArr.put(obj)
            }
            root.put("charges", chargesArr)
        }

        FileWriter(file).use { writer ->
            writer.write(sortedJsonString(root, 2))
        }
    }

    /**
     * Delete stale export files from cacheDir (older than 1 hour).
     * Called before each new export to prevent indefinite accumulation.
     */
    private fun cleanupStaleExports() {
        try {
            val cutoff = System.currentTimeMillis() - 3600_000L // 1 hour
            context.cacheDir.listFiles { file ->
                file.name.startsWith("matelink_export_") && file.lastModified() < cutoff
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Best-effort cleanup
        }
    }

    private fun sortedJsonString(obj: JSONObject, indent: Int): String {
        return sortKeysRecursive(obj).toString(indent)
    }

    private fun sortKeysRecursive(obj: JSONObject): JSONObject {
        val sorted = JSONObject()
        obj.keys().asSequence().sorted().forEach { key ->
            val value = obj.get(key)
            sorted.put(key, when (value) {
                is JSONObject -> sortKeysRecursive(value)
                is JSONArray -> sortKeysRecursive(value)
                else -> value
            })
        }
        return sorted
    }

    private fun sortKeysRecursive(arr: JSONArray): JSONArray {
        val sorted = JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            sorted.put(when (value) {
                is JSONObject -> sortKeysRecursive(value)
                is JSONArray -> sortKeysRecursive(value)
                else -> value
            })
        }
        return sorted
    }
}
