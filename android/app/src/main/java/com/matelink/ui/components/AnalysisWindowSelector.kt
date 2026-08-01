package com.matelink.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.matelink.R
import com.matelink.domain.analytics.AnalysisWindow
import java.time.LocalDate

@Composable
fun AnalysisWindowSelector(
    selected: AnalysisWindow,
    onSelected: (AnalysisWindow) -> Unit,
    onCustomSelected: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            AnalysisWindow.ALL_TIME to R.string.analysis_filter_all,
            AnalysisWindow.LAST_90_DAYS to R.string.analysis_filter_last_90_days,
            AnalysisWindow.SUMMER to R.string.analysis_filter_summer,
            AnalysisWindow.WINTER to R.string.analysis_filter_winter
        ).forEach { (window, label) ->
            FilterChip(
                selected = selected == window,
                onClick = { onSelected(window) },
                label = { Text(stringResource(label)) }
            )
        }
        FilterChip(
            selected = selected == AnalysisWindow.CUSTOM,
            onClick = { showDatePicker = true },
            label = { Text(stringResource(R.string.analysis_filter_custom)) }
        )
    }
    if (showDatePicker) {
        DateRangePickerDialog(
            onDismiss = { showDatePicker = false },
            onRangeSelected = { start, end ->
                showDatePicker = false
                onCustomSelected(start, end)
            }
        )
    }
}
