package com.matelink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.matelink.util.GCJ02Converter

/**
 * Compose wrapper for AMap (高德地图) MapView.
 * Handles lifecycle management via LifecycleEventObserver.
 */
@Composable
fun AmapComposeView(
    modifier: Modifier = Modifier,
    latitude: Double = 31.2304,
    longitude: Double = 121.4737,
    zoom: Float = 15f,
    routePoints: List<Pair<Double, Double>> = emptyList(),
    markers: List<Triple<Double, Double, String>> = emptyList(),
    onMapReady: ((AMap) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }

    // Bind MapView lifecycle to Compose lifecycle
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    val mapReadyFired = remember { mutableStateOf(false) }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    ) { map ->
        val aMap = map.map ?: return@AndroidView

        // Clear previous overlays to prevent accumulation
        aMap.clear()

        // Move camera to initial position
        val gcjPoint = GCJ02Converter.wgs84ToGcj02(latitude, longitude)
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(gcjPoint.first, gcjPoint.second), zoom
        ))

        // Add markers
        markers.forEach { (lat, lng, title) ->
            val gcjMarker = GCJ02Converter.wgs84ToGcj02(lat, lng)
            aMap.addMarker(
                MarkerOptions()
                    .position(LatLng(gcjMarker.first, gcjMarker.second))
                    .title(title)
            )
        }

        // Add route polyline
        if (routePoints.isNotEmpty()) {
            val gcjPoints = routePoints.map { (lat, lng) ->
                val gcj = GCJ02Converter.wgs84ToGcj02(lat, lng)
                LatLng(gcj.first, gcj.second)
            }
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(gcjPoints)
                    .color(0xFF1E88E5.toInt())
                    .width(8f)
            )
        }

        // Fire onMapReady only once
        if (!mapReadyFired.value) {
            mapReadyFired.value = true
            onMapReady?.invoke(aMap)
        }
    }
}

/**
 * Simplified AMap view for showing a single point (e.g., charge location).
 */
@Composable
fun AmapPointView(
    modifier: Modifier = Modifier,
    latitude: Double,
    longitude: Double,
    title: String = "",
    zoom: Float = 15f
) {
    AmapComposeView(
        modifier = modifier,
        latitude = latitude,
        longitude = longitude,
        zoom = zoom,
        markers = listOf(Triple(latitude, longitude, title))
    )
}

/**
 * AMap view for showing a route (e.g., drive detail).
 */
@Composable
fun AmapRouteView(
    modifier: Modifier = Modifier,
    routePoints: List<Pair<Double, Double>>,
    startTitle: String = "Start",
    endTitle: String = "End"
) {
    val markers = remember(routePoints) {
        if (routePoints.isNotEmpty()) {
            listOf(
                Triple(routePoints.first().first, routePoints.first().second, startTitle),
                Triple(routePoints.last().first, routePoints.last().second, endTitle)
            )
        } else emptyList()
    }

    AmapComposeView(
        modifier = modifier,
        latitude = routePoints.firstOrNull()?.first ?: 31.2304,
        longitude = routePoints.firstOrNull()?.second ?: 121.4737,
        zoom = 12f,
        routePoints = routePoints,
        markers = markers
    )
}
