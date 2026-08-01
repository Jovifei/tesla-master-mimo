package com.matelink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.matelink.R
import com.matelink.domain.map.AmapConfiguration
import com.matelink.ui.screens.map.AmapMapMarker
import com.matelink.ui.screens.map.AmapNativeMapView

@Composable
fun AmapPointView(
    modifier: Modifier = Modifier,
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    title: String = "",
    zoom: Float = 15f
) {
    if (!AmapConfiguration.isUsableCoordinate(latitude, longitude)) {
        AmapStatusMessage(R.string.amap_no_position, modifier)
        return
    }

    AmapMapGate(modifier = modifier) { apiKey, onLoading, onLoaded, onFailure ->
        AmapNativeMapView(
            apiKey = apiKey,
            modifier = Modifier.fillMaxSize(),
            center = latitude to longitude,
            markers = listOf(AmapMapMarker(latitude, longitude, title)),
            polylineColor = Color(0xFF0891B2),
            zoom = zoom,
            onLoading = onLoading,
            onLoaded = onLoaded,
            onFailure = onFailure
        )
    }
}
