package com.matelink.ui.screens.map

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions

private class AmapMapHandle {
    var view: MapView? = null
    var map: AMap? = null
    var marker: Marker? = null
}

/** Creates MapView only after key and explicit privacy consent have both been checked by the caller. */
@Composable
fun AmapMapView(
    apiKey: String,
    latitude: Double?,
    longitude: Double?,
    onLoading: () -> Unit,
    onLoaded: () -> Unit,
    onFailure: () -> Unit
) {
    val context = LocalContext.current
    val savedState = rememberSaveable { Bundle() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle = remember { AmapMapHandle() }

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { handle.view?.onResume() }
            override fun onPause(owner: LifecycleOwner) { handle.view?.onPause(); handle.view?.onSaveInstanceState(savedState) }
            override fun onDestroy(owner: LifecycleOwner) { destroyMap(handle) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyMap(handle)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            try {
                onLoading()
                MapsInitializer.updatePrivacyShow(context, true, true)
                MapsInitializer.updatePrivacyAgree(context, true)
                MapsInitializer.setApiKey(apiKey)
                AmapSdkGate.wasInitialized = true
                MapView(context).also { view ->
                    handle.view = view
                    view.onCreate(savedState)
                    val map: AMap = view.map
                    handle.map = map
                    map.uiSettings.isZoomControlsEnabled = true
                    map.setOnMapLoadedListener { onLoaded() }
                    updateVehicleMarker(handle, latitude, longitude)
                }
            } catch (_: Exception) {
                onFailure()
                android.widget.FrameLayout(context)
            }
        },
        update = { updateVehicleMarker(handle, latitude, longitude) }
    )
}

private fun updateVehicleMarker(handle: AmapMapHandle, latitude: Double?, longitude: Double?) {
    val map = handle.map ?: return
    if (latitude == null || longitude == null) {
        handle.marker?.remove()
        handle.marker = null
        return
    }
    val point = LatLng(latitude, longitude)
    val marker = handle.marker ?: map.addMarker(MarkerOptions().position(point).title("Vehicle")).also { handle.marker = it }
    marker.position = point
    map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 14f))
}

private fun destroyMap(handle: AmapMapHandle) {
    handle.marker = null
    handle.map = null
    handle.view?.onDestroy()
    handle.view = null
}
