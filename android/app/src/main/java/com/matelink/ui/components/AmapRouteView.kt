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
fun AmapRouteView(
    modifier: Modifier = Modifier,
    points: List<Pair<Double, Double>> = emptyList(),
    routePoints: List<Pair<Double, Double>> = emptyList(),
    startTitle: String = "",
    endTitle: String = "",
    routeColor: Color = Color(0xFF0891B2),
    zoom: Float = 12f
) {
    val route = (routePoints.ifEmpty { points })
        .filter { AmapConfiguration.isUsableCoordinate(it.first, it.second) }
    if (route.isEmpty()) {
        AmapStatusMessage(R.string.amap_no_position, modifier)
        return
    }

    val markers = buildList {
        val start = route.first()
        add(AmapMapMarker(start.first, start.second, startTitle))
        if (route.size > 1) {
            val end = route.last()
            add(AmapMapMarker(end.first, end.second, endTitle))
        }
    }
    AmapMapGate(modifier = modifier) { apiKey, onLoading, onLoaded, onFailure ->
        AmapNativeMapView(
            apiKey = apiKey,
            modifier = Modifier.fillMaxSize(),
            center = route.first(),
            markers = markers,
            polylinePoints = route,
            polylineColor = routeColor,
            zoom = zoom,
            onLoading = onLoading,
            onLoaded = onLoaded,
            onFailure = onFailure
        )
    }
}
