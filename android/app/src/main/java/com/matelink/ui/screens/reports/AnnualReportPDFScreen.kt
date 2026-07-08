package com.matelink.ui.screens.reports

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.domain.model.CarStats
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnualReportPDFScreen(
    carId: Int = 1,
    year: Int = 2025,
    onBack: () -> Unit = {},
    viewModel: AnnualReportPDFViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(carId, year) {
        viewModel.init(carId, year)
    }

    // Share PDF when path is available
    LaunchedEffect(uiState.pdfPath) {
        uiState.pdfPath?.let { path ->
            viewModel.clearPdfPath() // clear first to prevent re-trigger on recomposition
            try {
                val file = File(path)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.pdf_report_share_title)))
            } catch (e: Exception) {
                android.util.Log.e("AnnualReportPDF", "Failed to share PDF", e)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdf_report_title, uiState.year)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Year selector
            if (uiState.availableYears.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    uiState.availableYears.forEach { y ->
                        FilterChip(
                            selected = y == uiState.year,
                            onClick = { viewModel.selectYear(y) },
                            label = { Text(y.toString()) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            // Preview card
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.annual_report_title, uiState.year),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pdf_report_description, uiState.year),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    uiState.carStats?.let { stats ->
                        val qs = stats.quickStats
                        Text("${stringResource(R.string.pdf_report_total_distance)} ${String.format(java.util.Locale.US, "%,.0f", qs.totalDistanceKm)} km")
                        Text("${stringResource(R.string.pdf_report_total_drives)} ${qs.totalDrives}")
                        Text("${stringResource(R.string.pdf_report_energy_used)} ${String.format(java.util.Locale.US, "%,.1f", qs.totalEnergyConsumedKwh)} kWh")
                        Text("${stringResource(R.string.pdf_report_charges_label)} ${qs.totalCharges}")
                        Text("${stringResource(R.string.pdf_report_avg_efficiency)} ${String.format(java.util.Locale.US, "%.0f", qs.avgEfficiencyWhKm)} Wh/km")
                    }
                }
            }

            // Generate button
            Button(
                onClick = { viewModel.generatePdf() },
                enabled = uiState.carStats != null && !uiState.isGeneratingPdf,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isGeneratingPdf) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.pdf_report_generate_share))
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ==================== PDF Generation ====================

private const val PAGE_W = 595  // A4 width in points
private const val PAGE_H = 842  // A4 height in points
private const val MARGIN = 50f
private val CONTENT_W get() = PAGE_W - 2 * MARGIN

private class PdfBuilder(private val context: Context) {
    private val doc = PdfDocument()
    private var pageNum = 0
    private var y = MARGIN
    private lateinit var currentPage: PdfDocument.Page
    private val canvas: Canvas get() = currentPage.canvas

    private val titlePaint = Paint().apply {
        textSize = 28f; isFakeBoldText = true; isAntiAlias = true
    }
    private val h2Paint = Paint().apply {
        textSize = 18f; isFakeBoldText = true; isAntiAlias = true
    }
    private val h3Paint = Paint().apply {
        textSize = 14f; isFakeBoldText = true; isAntiAlias = true
    }
    private val bodyPaint = Paint().apply {
        textSize = 12f; isAntiAlias = true
    }
    private val smallPaint = Paint().apply {
        textSize = 10f; color = android.graphics.Color.GRAY; isAntiAlias = true
    }
    private val dividerPaint = Paint().apply {
        color = android.graphics.Color.LTGRAY; strokeWidth = 1f
    }
    private val barPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#1E88E5"); isAntiAlias = true
    }

    private fun newPage() {
        if (pageNum > 0) doc.finishPage(currentPage)
        pageNum++
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
        currentPage = doc.startPage(info)
        y = MARGIN
    }

    fun text(text: String, paint: Paint, lineSpacing: Float = 20f) {
        if (y + lineSpacing > PAGE_H - MARGIN) newPage()
        canvas.drawText(text, MARGIN, y + paint.textSize, paint)
        y += lineSpacing
    }

    fun textRight(text: String, paint: Paint, lineSpacing: Float = 20f) {
        if (y + lineSpacing > PAGE_H - MARGIN) newPage()
        val w = paint.measureText(text)
        canvas.drawText(text, PAGE_W - MARGIN - w, y + paint.textSize, paint)
        y += lineSpacing
    }

    fun divider(lineSpacing: Float = 12f) {
        if (y + lineSpacing > PAGE_H - MARGIN) newPage()
        canvas.drawLine(MARGIN, y + 4, PAGE_W - MARGIN, y + 4, dividerPaint)
        y += lineSpacing
    }

    fun spacer(h: Float = 12f) {
        if (y + h > PAGE_H - MARGIN) newPage()
        y += h
    }

    fun barChart(values: List<Float>, labels: List<String>, chartH: Float = 100f) {
        if (y + chartH + 20 > PAGE_H - MARGIN) newPage()
        val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val barW = CONTENT_W / (values.size * 2f)
        val baseY = y + chartH

        values.forEachIndexed { i, v ->
            val bh = (v / max) * chartH
            val x = MARGIN + i * (CONTENT_W / values.size) + barW / 2
            canvas.drawRect(x, baseY - bh, x + barW, baseY, barPaint)
        }
        y = baseY + 4

        val labelPaint = Paint(smallPaint).apply { textSize = 9f }
        val step = CONTENT_W / values.size
        labels.forEachIndexed { i, l ->
            canvas.drawText(l, MARGIN + i * step + step / 2 - labelPaint.measureText(l) / 2, y + 10, labelPaint)
        }
        y += 18
    }

    fun build(
        stats: CarStats,
        year: Int,
        monthlyDrives: List<MonthlyDriveAggregation> = emptyList(),
        monthlyCharges: List<MonthlyChargeAggregation> = emptyList()
    ): String {
        val qs = stats.quickStats

        newPage()

        // Title
        text(context.getString(R.string.annual_report_title, year), titlePaint, 36f)
        text(context.getString(R.string.pdf_report_vehicle_summary), smallPaint, 18f)
        divider(20f)

        // Summary
        text(context.getString(R.string.annual_report_summary), h2Paint, 28f)
        text("${context.getString(R.string.pdf_report_total_distance)} ${String.format(java.util.Locale.US, "%,.0f", qs.totalDistanceKm)} km", bodyPaint)
        text("${context.getString(R.string.pdf_report_total_drives)} ${qs.totalDrives}", bodyPaint)
        text("${context.getString(R.string.pdf_report_energy_used)} ${String.format(java.util.Locale.US, "%,.1f", qs.totalEnergyConsumedKwh)} kWh", bodyPaint)
        text("${context.getString(R.string.pdf_report_avg_efficiency)} ${String.format(java.util.Locale.US, "%.0f", qs.avgEfficiencyWhKm)} Wh/km", bodyPaint)
        text("${context.getString(R.string.pdf_report_charges_label)} ${qs.totalCharges}", bodyPaint)
        text("${context.getString(R.string.pdf_report_energy_added_label)} ${String.format(java.util.Locale.US, "%,.1f", qs.totalEnergyAddedKwh)} kWh", bodyPaint)
        if (qs.totalCost != null && qs.totalCost > 0) {
            text("${context.getString(R.string.pdf_report_total_cost_label)} ${String.format(java.util.Locale.US, "%.2f", qs.totalCost)}", bodyPaint)
        }
        spacer(8f)

        // Records
        if (qs.longestDrive != null || qs.fastestDrive != null || qs.mostEfficientDrive != null) {
            divider(16f)
            text(context.getString(R.string.stats_records), h2Paint, 28f)
            qs.longestDrive?.let {
                text("${context.getString(R.string.pdf_report_longest_drive)} ${String.format(java.util.Locale.US, "%.1f", it.distance)} km (${it.startDate})", bodyPaint)
            }
            qs.fastestDrive?.let {
                text("${context.getString(R.string.pdf_report_fastest_drive)} ${it.speedMax} km/h (${it.startDate})", bodyPaint)
            }
            qs.mostEfficientDrive?.let {
                text("${context.getString(R.string.pdf_report_most_efficient)} ${String.format(java.util.Locale.US, "%.0f", it.efficiency)} Wh/km (${it.startDate})", bodyPaint)
            }
        }

        // Monthly trends
        divider(16f)
        text(context.getString(R.string.annual_report_monthly_trends), h2Paint, 28f)

        val months = java.text.DateFormatSymbols().shortMonths.toList()

        if (monthlyDrives.isNotEmpty()) {
            text(context.getString(R.string.pdf_report_distance_by_month), h3Paint, 22f)
            val distanceByMonth = FloatArray(12)
            monthlyDrives.forEach { if (it.month in 1..12) distanceByMonth[it.month - 1] = it.totalDistance.toFloat() }
            barChart(distanceByMonth.toList(), months, 100f)
            spacer(8f)

            text(context.getString(R.string.pdf_report_energy_consumed_by_month), h3Paint, 22f)
            val energyByMonth = FloatArray(12)
            monthlyDrives.forEach { if (it.month in 1..12) energyByMonth[it.month - 1] = it.totalEnergy.toFloat() }
            barChart(energyByMonth.toList(), months, 100f)
            spacer(8f)
        }

        if (monthlyCharges.isNotEmpty()) {
            text(context.getString(R.string.pdf_report_energy_added_by_month), h3Paint, 22f)
            val chargeEnergyByMonth = FloatArray(12)
            monthlyCharges.forEach { if (it.month in 1..12) chargeEnergyByMonth[it.month - 1] = it.totalEnergy.toFloat() }
            barChart(chargeEnergyByMonth.toList(), months, 100f)
        }

        if (monthlyDrives.isEmpty() && monthlyCharges.isEmpty()) {
            text(context.getString(R.string.pdf_report_no_monthly_data), bodyPaint)
        }

        // Driving habits
        divider(16f)
        text(context.getString(R.string.annual_report_driving_habits), h2Paint, 28f)
        qs.avgDriveMinutes?.let { text("${context.getString(R.string.pdf_report_avg_drive_duration)} ${String.format(java.util.Locale.US, "%.0f", it)} min", bodyPaint) }
        qs.totalDrivingDays?.let { text("${context.getString(R.string.pdf_report_driving_days)} $it", bodyPaint) }
        qs.maxSpeedKmh?.let { text("${context.getString(R.string.pdf_report_top_speed_label)} $it km/h", bodyPaint) }
        if (qs.avgEfficiencyWhKm > 0) {
            val rating = when {
                qs.avgEfficiencyWhKm < 150 -> context.getString(R.string.annual_report_excellent)
                qs.avgEfficiencyWhKm < 180 -> context.getString(R.string.annual_report_good)
                qs.avgEfficiencyWhKm < 220 -> context.getString(R.string.annual_report_average)
                else -> context.getString(R.string.annual_report_high)
            }
            text("${context.getString(R.string.pdf_report_efficiency_rating_label)} $rating", bodyPaint)
        }

        // Footer
        spacer(24f)
        divider(12f)
        text(context.getString(R.string.pdf_report_generated_by), smallPaint, 14f)

        doc.finishPage(currentPage)

        // Save (clean up previous version first)
        val fileName = "matelink_annual_report_${year}.pdf"
        val file = File(context.cacheDir, fileName)
        if (file.exists()) file.delete()
        try {
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }

        return file.absolutePath
    }
}

fun generatePdf(
    context: Context,
    stats: CarStats,
    year: Int,
    monthlyDrives: List<MonthlyDriveAggregation> = emptyList(),
    monthlyCharges: List<MonthlyChargeAggregation> = emptyList()
): String {
    return PdfBuilder(context).build(stats, year, monthlyDrives, monthlyCharges)
}
