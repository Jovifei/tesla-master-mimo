package com.matelink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.matelink.R
import com.matelink.data.repository.AmapCoordinateTransformer
import com.matelink.ui.screens.map.AmapMapMarker
import com.matelink.ui.screens.map.AmapNativeMapView

@Composable
fun AmapComposeView(
    modifier: Modifier = Modifier,
    latitude: Double? = null,
    longitude: Double? = null,
    zoom: Float = 15f,
    markers: List<Pair<Double, Double>> = emptyList(),
    polylinePoints: List<Pair<Double, Double>> = emptyList()
) {
    val center = AmapCoordinateTransformer.normalize(latitude, longitude)
        ?.let { it.latitude to it.longitude }
    val validMarkers = markers.mapNotNull { point ->
        AmapCoordinateTransformer.normalize(point.first, point.second)
            ?.let { AmapMapMarker(it.latitude, it.longitude) }
    }
    val validPolyline = polylinePoints.mapNotNull { point ->
        AmapCoordinateTransformer.normalize(point.first, point.second)
            ?.let { it.latitude to it.longitude }
    }
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
            sourceCoordinateSystem = com.matelink.data.repository.CoordinateSystem.GCJ02,
            zoom = zoom,
            onLoading = onLoading,
            onLoaded = onLoaded,
            onFailure = onFailure
        )
    }
}
