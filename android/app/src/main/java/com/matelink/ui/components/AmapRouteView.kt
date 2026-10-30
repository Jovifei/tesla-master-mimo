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
    zoom: Float = 12f,
    routeSegments: List<List<Pair<Double, Double>>>? = null,
    explicitEndpoints: Boolean = false,
    startPoint: Pair<Double, Double>? = null,
    endPoint: Pair<Double, Double>? = null
) {
    val segments = (routeSegments ?: listOf(routePoints.ifEmpty { points })).map { segment ->
        segment.filter { AmapConfiguration.isUsableCoordinate(it.first, it.second) }
    }
    val route = segments.flatten()
    if (route.isEmpty()) {
        AmapStatusMessage(R.string.amap_no_position, modifier)
        return
    }

    val markers = buildList {
        val start = if (explicitEndpoints) startPoint else route.first()
        start?.takeIf { AmapConfiguration.isUsableCoordinate(it.first, it.second) }
            ?.let { add(AmapMapMarker(it.first, it.second, startTitle)) }
        val end = if (explicitEndpoints) endPoint else route.lastOrNull().takeIf { route.size > 1 }
        end?.takeIf { AmapConfiguration.isUsableCoordinate(it.first, it.second) }
            ?.let { add(AmapMapMarker(it.first, it.second, endTitle)) }
    }
    AmapMapGate(modifier = modifier) { apiKey, onLoading, onLoaded, onFailure ->
        AmapNativeMapView(
            apiKey = apiKey,
            modifier = Modifier.fillMaxSize(),
            center = route.first(),
            markers = markers,
            polylinePoints = route,
            polylineSegments = segments,
            polylineColor = routeColor,
            zoom = zoom,
            onLoading = onLoading,
            onLoaded = onLoaded,
            onFailure = onFailure
        )
    }
}
