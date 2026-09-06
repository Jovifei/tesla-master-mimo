package com.matelink.ui.screens.map

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.matelink.data.repository.AmapCoordinateTransformer
import com.matelink.data.repository.CoordinateSystem

data class AmapMapMarker(
    val latitude: Double,
    val longitude: Double,
    val title: String = ""
)

private data class AmapMapContent(
    val center: Pair<Double, Double>?,
    val markers: List<AmapMapMarker>,
    val polylinePoints: List<Pair<Double, Double>>,
    val polylineColor: Int,
    val zoom: Float
)

private class AmapMapHandle {
    var view: MapView? = null
    var map: AMap? = null
    var loaded: Boolean = false
    var content: AmapMapContent? = null
    var renderedContent: AmapMapContent? = null
}

@Composable
fun AmapMapView(
    apiKey: String,
    latitude: Double?,
    longitude: Double?,
    markerTitle: String,
    onLoading: () -> Unit,
    onLoaded: () -> Unit,
    onFailure: () -> Unit
) {
    val coordinate = AmapCoordinateTransformer.normalize(latitude, longitude)
    AmapNativeMapView(
        apiKey = apiKey,
        center = coordinate?.let { it.latitude to it.longitude },
        markers = coordinate?.let { listOf(AmapMapMarker(it.latitude, it.longitude, markerTitle)) }.orEmpty(),
        sourceCoordinateSystem = CoordinateSystem.GCJ02,
        zoom = 14f,
        onLoading = onLoading,
        onLoaded = onLoaded,
        onFailure = onFailure
    )
}

/** Lifecycle-safe native AMap renderer shared by point, route, and multi-marker pages. */
@Composable
fun AmapNativeMapView(
    apiKey: String,
    modifier: Modifier = Modifier,
    center: Pair<Double, Double>? = null,
    markers: List<AmapMapMarker> = emptyList(),
    polylinePoints: List<Pair<Double, Double>> = emptyList(),
    polylineColor: Color = Color(0xFF0891B2),
    zoom: Float = 15f,
    sourceCoordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    onLoading: () -> Unit = {},
    onLoaded: () -> Unit = {},
    onFailure: () -> Unit = {}
) {
    val context = LocalContext.current
    val savedState = rememberSaveable { Bundle() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle = remember { AmapMapHandle() }
    val validCenter = center?.let { AmapCoordinateTransformer.normalize(it.first, it.second, sourceCoordinateSystem) }
        ?.let { it.latitude to it.longitude }
    val validMarkers = markers.mapNotNull { marker ->
        AmapCoordinateTransformer.normalize(marker.latitude, marker.longitude, sourceCoordinateSystem)
            ?.let { marker.copy(latitude = it.latitude, longitude = it.longitude) }
    }
    val validPolyline = polylinePoints.mapNotNull { point ->
        AmapCoordinateTransformer.normalize(point.first, point.second, sourceCoordinateSystem)
            ?.let { it.latitude to it.longitude }
    }

    handle.content = AmapMapContent(
        center = validCenter,
        markers = validMarkers,
        polylinePoints = validPolyline,
        polylineColor = polylineColor.toArgb(),
        zoom = zoom
    )

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { handle.view?.onResume() }
            override fun onPause(owner: LifecycleOwner) {
                handle.view?.onPause()
                handle.view?.onSaveInstanceState(savedState)
            }
            override fun onDestroy(owner: LifecycleOwner) { destroyMap(handle) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyMap(handle)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            try {
                onLoading()
                MapsInitializer.updatePrivacyShow(context, true, true)
                MapsInitializer.updatePrivacyAgree(context, true)
                MapsInitializer.setApiKey(apiKey)
                MapsInitializer.setProtocol(2)
                AmapSdkGate.wasInitialized = true
                MapView(context).also { view ->
                    handle.view = view
                    view.onCreate(savedState)
                    view.onResume()
                    handle.map = view.map.apply {
                        uiSettings.isZoomControlsEnabled = true
                        setOnMapLoadedListener {
                            handle.loaded = true
                            renderMapContent(handle, onFailure)
                            onLoaded()
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("AmapNativeMapView", "AMap initialization failed: ${error.javaClass.simpleName}")
                onFailure()
                android.widget.FrameLayout(context)
            }
        },
        update = {
            if (handle.loaded) renderMapContent(handle, onFailure)
        }
    )
}

private fun renderMapContent(handle: AmapMapHandle, onFailure: () -> Unit) {
    val map = handle.map ?: return
    val content = handle.content ?: return
    if (content == handle.renderedContent) return
    try {
        map.clear()
        content.markers.forEach { marker ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(marker.latitude, marker.longitude))
                    .title(marker.title)
            )
        }
        if (content.polylinePoints.size >= 2) {
            map.addPolyline(
                PolylineOptions()
                    .addAll(content.polylinePoints.map { LatLng(it.first, it.second) })
                    .color(content.polylineColor)
                    .width(10f)
            )
        }

        val cameraPoints = (
            content.polylinePoints + content.markers.map { it.latitude to it.longitude }
        ).distinct()
        when {
            cameraPoints.size >= 2 -> {
                val builder = LatLngBounds.Builder()
                cameraPoints.forEach { builder.include(LatLng(it.first, it.second)) }
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 72))
            }
            cameraPoints.size == 1 -> {
                val point = cameraPoints.first()
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.first, point.second), content.zoom))
            }
            content.center != null -> {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(content.center.first, content.center.second),
                        content.zoom
                    )
                )
            }
        }
        handle.renderedContent = content
    } catch (error: Exception) {
        Log.e("AmapNativeMapView", "AMap rendering failed: ${error.javaClass.simpleName}")
        onFailure()
    }
}

private fun destroyMap(handle: AmapMapHandle) {
    handle.loaded = false
    handle.renderedContent = null
    handle.map = null
    try {
        handle.view?.onPause()
        handle.view?.onDestroy()
    } catch (_: Exception) {}
    handle.view = null
}
