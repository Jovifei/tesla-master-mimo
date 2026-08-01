package com.matelink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.matelink.R
import com.matelink.domain.map.AmapConfiguration
import com.matelink.ui.screens.map.AmapMapMarker
import com.matelink.ui.screens.map.AmapNativeMapView

@Composable
fun AmapComposeView(
    modifier: Modifier = Modifier,
    latitude: Double = 31.2304,
    longitude: Double = 121.4737,
    zoom: Float = 15f,
    markers: List<Pair<Double, Double>> = emptyList(),
    polylinePoints: List<Pair<Double, Double>> = emptyList()
) {
    val center = (latitude to longitude)
        .takeIf { AmapConfiguration.isUsableCoordinate(it.first, it.second) }
    val validMarkers = markers
        .filter { AmapConfiguration.isUsableCoordinate(it.first, it.second) }
        .map { AmapMapMarker(it.first, it.second) }
    val validPolyline = polylinePoints
        .filter { AmapConfiguration.isUsableCoordinate(it.first, it.second) }
    if (center == null && validMarkers.isEmpty() && validPolyline.isEmpty()) {
        AmapStatusMessage(R.string.amap_no_position, modifier)
        return
    }

    AmapMapGate(modifier = modifier) { apiKey, onLoading, onLoaded, onFailure ->
        AmapNativeMapView(
            apiKey = apiKey,
            modifier = Modifier.fillMaxSize(),
            center = center,
            markers = validMarkers,
            polylinePoints = validPolyline,
            zoom = zoom,
            onLoading = onLoading,
            onLoaded = onLoaded,
            onFailure = onFailure
        )
    }
}
