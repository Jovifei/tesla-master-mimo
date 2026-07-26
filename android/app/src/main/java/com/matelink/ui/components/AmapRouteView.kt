package com.matelink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.matelink.R

/**
 * Placeholder for AMap route view.
 * Amap SDK requires manual AAR download from https://lbs.amap.com.
 */
@Composable
fun AmapRouteView(
    modifier: Modifier = Modifier,
    points: List<Pair<Double, Double>> = emptyList(),
    routePoints: List<Pair<Double, Double>> = emptyList(),
    startTitle: String = "",
    endTitle: String = "",
    zoom: Float = 12f
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE8F5E9)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.amap_legacy_preview_unavailable),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color.Gray
        )
    }
}
