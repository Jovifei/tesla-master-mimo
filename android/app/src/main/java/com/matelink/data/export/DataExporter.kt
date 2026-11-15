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
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.toAnalysisChargeData

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    JSON("json", "application/json")
}

enum class ExportDataType {
    DRIVES,
    CHARGES,
    BOTH
}

class DataExporter(
    private val context: Context,
    chargeCosts: Map<Int, EffectiveChargeCost> = emptyMap()
) {
    private val chargeCosts = chargeCosts.toMap()

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
        val file = File.createTempFile("matelink_export_", ".${format.extension}", context.cacheDir)

        try {
            when (format) {
                ExportFormat.CSV -> exportCsv(file, drives, charges, dataType)
                ExportFormat.JSON -> exportJson(file, drives, charges, dataType)
            }
            FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
            )
        } catch (e: Exception) {
            // A completed unique file may already have a FileProvider consumer;
            // never reuse its path for another request.
            throw e
        }
    }

    private fun csvEscape(value: Any?): String {
        // A signed Number is a number, never a spreadsheet formula. Text is
        // defanged before quoting so a comma cannot hide a formula prefix.
        if (value is Number) return value.toString()
        val raw = value?.toString() ?: ""
        val s = if (raw.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) "'$raw" else raw
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"${s.replace("\"", "\"\"")}\"" else s
    }

    private fun exportCsv(
        file: File,
        drives: List<DriveSummary>,
        charges: List<ChargeSummary>,
        dataType: ExportDataType
    ) {
        FileWriter(file).use { writer ->
            if (dataType == ExportDataType.DRIVES || dataType == ExportDataType.BOTH) {
                writer.append("Type,Start Date,End Date,Raw Summary Distance (km),Raw Summary Energy (kWh),Raw Summary Efficiency (Wh/km),Raw Summary Max Speed (km/h),Raw Summary Duration (min),Qualified Distance (km),Qualified Energy (kWh),Qualified Efficiency (Wh/km),Source,Quality\n")
                drives.forEach { d ->
                    val qualified = d.toAnalysisDriveData()
                    writer.append("DRIVE,${csvEscape(d.startDate)},${csvEscape(d.endDate)},${d.distance},${csvEscape(d.energyConsumed)},${csvEscape(d.efficiency)},${csvEscape(d.speedMax)},${d.durationMin},${csvEscape(qualified.distanceKm)},${csvEscape(qualified.netEnergyKwh)},${csvEscape(qualified.efficiencyWhKm)},${csvEscape(qualified.source)},${csvEscape(d.qualityState)}\n")
                }
            }
            if (dataType == ExportDataType.CHARGES || dataType == ExportDataType.BOTH) {
                if (dataType == ExportDataType.BOTH) writer.append("\n")
                writer.append("Type,Start Date,End Date,Raw Summary Energy Added (kWh),Raw Cost,Raw Summary Duration (min),Raw Summary Start Battery %,Raw Summary End Battery %,Raw Currency,Qualified Battery Input (kWh),Effective Cost,Effective Currency,Cost Source,Source,Quality\n")
                charges.forEach { c ->
                    val qualified = c.toAnalysisChargeData()
                    val cost = chargeCosts[c.chargeId]
                    writer.append("CHARGE,${csvEscape(c.startDate)},${csvEscape(c.endDate)},${c.energyAdded},${csvEscape(c.cost)},${csvEscape(c.durationMin)},${csvEscape(c.startBatteryLevel)},${csvEscape(c.endBatteryLevel)},${csvEscape(qualified.currencyCode)},${csvEscape(qualified.batteryInputKwh)},${csvEscape(cost?.cost)},${csvEscape(cost?.currencyCode)},${csvEscape(cost?.source?.name)},${csvEscape(qualified.source)},${csvEscape(c.qualityState)}\n")
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
        root.put("dataContract", "raw_summary_and_qualified_projection_v1")
        root.put("legacyScalarFields", "raw_stored_summary_not_measurement_proof")
        root.put("exportDate", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(System.currentTimeMillis()))

        if (dataType == ExportDataType.DRIVES || dataType == ExportDataType.BOTH) {
            val drivesArr = JSONArray()
            drives.forEach { d ->
                val obj = JSONObject()
                obj.put("startDate", d.startDate)
                obj.put("endDate", d.endDate ?: JSONObject.NULL)
                obj.put("distanceKm", d.distance)
                obj.put("energyKwh", d.energyConsumed ?: JSONObject.NULL)
                obj.put("efficiencyWhKm", d.efficiency ?: JSONObject.NULL)
                obj.put("maxSpeedKmh", d.speedMax ?: JSONObject.NULL)
                obj.put("durationMin", d.durationMin)
                val qualified = d.toAnalysisDriveData()
                obj.put("qualifiedDistanceKm", qualified.distanceKm ?: JSONObject.NULL)
                obj.put("qualifiedEnergyKwh", qualified.netEnergyKwh ?: JSONObject.NULL)
                obj.put("qualifiedEfficiencyWhKm", qualified.efficiencyWhKm ?: JSONObject.NULL)
                obj.put("source", qualified.source ?: JSONObject.NULL)
                obj.put("qualityState", d.qualityState)
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
                val qualified = c.toAnalysisChargeData()
                val cost = chargeCosts[c.chargeId]
                obj.put("rawCost", c.cost ?: JSONObject.NULL)
                obj.put("currencyCode", qualified.currencyCode ?: JSONObject.NULL)
                obj.put("qualifiedBatteryInputKwh", qualified.batteryInputKwh ?: JSONObject.NULL)
                obj.put("effectiveCost", cost?.cost ?: JSONObject.NULL)
                obj.put("effectiveCurrencyCode", cost?.currencyCode ?: JSONObject.NULL)
                obj.put("costSource", cost?.source?.name ?: JSONObject.NULL)
                obj.put("source", qualified.source ?: JSONObject.NULL)
                obj.put("qualityState", c.qualityState)
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
