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
import androidx.compose.ui.text.style.TextAlign

/**
 * Placeholder for AMap (高德地图) MapView.
 * Amap SDK requires manual AAR download from https://lbs.amap.com.
 * This placeholder is shown when the SDK is not available.
 */
@Composable
fun AmapComposeView(
    modifier: Modifier = Modifier,
    latitude: Double = 31.2304,
    longitude: Double = 121.4737,
    zoom: Float = 15f,
    markers: List<Pair<Double, Double>> = emptyList(),
    polylinePoints: List<Pair<Double, Double>> = emptyList()
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE8F5E9)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Map requires Amap SDK.\nDownload from lbs.amap.com",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color.Gray
        )
    }
}
