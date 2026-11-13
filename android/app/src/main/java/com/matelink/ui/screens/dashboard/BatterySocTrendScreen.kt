package com.matelink.ui.screens.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matelink.R
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.UnifiedHistory
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.domain.history.isAnalysisEligible
import com.matelink.ui.components.FullscreenLineChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.TimedChartSample
import com.matelink.ui.components.TimedChartSeries
import com.matelink.ui.components.projectTimedChartSeries
import com.matelink.util.parseIsoInstant
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

@HiltViewModel
class BatterySocTrendViewModel @Inject constructor(
    private val history: UnifiedHistoryRepository,
    private val contexts: VehicleContextRepository,
    private val settings: SettingsRepository
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeWeek(carId: Int) =
        combine(contexts.historyScopeChanges, settings.currentCarId, ::Pair).transformLatest { (scope, selected) ->
            if (scope == null || selected != carId) {
                emit(BatterySocWeek())
                return@transformLatest
            }
            emit(null)
            val cached = try { (history.loadCached(carId) as? ApiResult.Success)?.data }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val proof = cached?.verifiedReadContext
            val series = if (cached != null && proof != null && proof.scope == scope && history.isContextCurrent(proof)) {
                projectBatterySocWeek(cached, Instant.now(), ZoneId.systemDefault())
            } else null
            emit(BatterySocWeek(series))
        }
}

internal data class BatterySocWeek(val series: TimedChartSeries? = null)

internal fun projectBatterySocWeek(history: UnifiedHistory, now: Instant, zone: ZoneId): TimedChartSeries {
    data class Reading(val at: Long, val percent: Int, val source: Pair<String?, String?>)
    val start = now.atZone(zone).toLocalDate().minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = now.toEpochMilli()
    val readings = buildList {
        fun addReading(date: String?, percent: Int?, source: Pair<String?, String?>) {
            val at = parseIsoInstant(date)?.toEpochMilli() ?: return
            if (at in start..end && percent != null && percent in 0..100) add(Reading(at, percent, source))
        }
        history.drives.filter { isAnalysisEligible(it.qualityState, it.qualityReason) }.forEach {
            addReading(it.startDate, it.startBatteryLevel, it.source to it.sourceInstanceId)
            addReading(it.endDate, it.endBatteryLevel, it.source to it.sourceInstanceId)
        }
        history.charges.filter { isAnalysisEligible(it.qualityState, it.qualityReason) }.forEach {
            addReading(it.startDate, it.startBatteryLevel, it.source to it.sourceInstanceId)
            addReading(it.endDate, it.endBatteryLevel, it.source to it.sourceInstanceId)
        }
    }.sortedBy { it.at }
    val series = projectTimedChartSeries(readings.mapIndexed { i, r -> TimedChartSample(r.at, r.percent.toDouble(), i) },
        start, end, maxGapMs = 36 * 60 * 60 * 1000L)
    val sourceBreaks = series.sourceIndices.zipWithNext().mapIndexedNotNull { index, (before, after) ->
        (index + 1).takeIf { readings[before].source != readings[after].source }
    }
    return series.copy(gaps = series.gaps + sourceBreaks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatterySocTrendScreen(
    carId: Int,
    onNavigateBack: () -> Unit,
    viewModel: BatterySocTrendViewModel = hiltViewModel()
) {
    val state by remember(viewModel, carId) { viewModel.observeWeek(carId) }.collectAsStateWithLifecycle(initialValue = null)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.battery_trend)) }, navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { padding ->
        val series = state?.series
        when {
            state == null -> MateLinkLoadingPlaceholder(color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize().padding(padding))
            series == null || series.validCount < 2 -> Box(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                val latest = series?.values?.lastOrNull(Float::isFinite)
                Text(if (latest == null) stringResource(R.string.battery_soc_week_empty)
                    else stringResource(R.string.battery_soc_week_single, latest.toInt()))
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                val date = DateTimeFormatter.ofPattern("M/d").withZone(ZoneId.systemDefault())
                fun timeLabel(at: Long?) = at?.let { date.format(Instant.ofEpochMilli(it)) }.orEmpty()
                FullscreenLineChart(
                    data = series.values,
                    xFractions = series.xFractions,
                    sourceIndices = series.sourceIndices,
                    gaps = series.gaps,
                    color = MaterialTheme.colorScheme.primary,
                    unit = "%",
                    fixedMinMax = 0f to 100f,
                    timeLabels = listOf(0f, .25f, .5f, .75f, 1f).map { timeLabel(series.axisTimestampAtFraction(it)) },
                    fractionToTimeLabel = { timeLabel(series.nearestTimestampAtFraction(it)) }
                )
            }
        }
    }
}
