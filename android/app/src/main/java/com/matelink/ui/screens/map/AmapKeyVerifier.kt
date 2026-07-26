package com.matelink.ui.screens.map

import android.content.Context
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val AMAP_SUCCESS_CODE = 1000
private const val KEY_VERIFICATION_TIMEOUT_MS = 15_000L

/**
 * Verifies an Android SDK Key through an SDK POI request. MapView's loaded
 * callback only proves that a view was created; the search response code is
 * the SDK's explicit authentication result.
 */
internal suspend fun verifyAmapAndroidKey(context: Context, apiKey: String): Boolean {
    return withTimeoutOrNull(KEY_VERIFICATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            try {
                MapsInitializer.updatePrivacyShow(context, true, true)
                MapsInitializer.updatePrivacyAgree(context, true)
                MapsInitializer.setApiKey(apiKey)
                ServiceSettings.updatePrivacyShow(context, true, true)
                ServiceSettings.updatePrivacyAgree(context, true)
                ServiceSettings.getInstance().setApiKey(apiKey)

                val query = PoiSearch.Query("Beijing", "", "010").apply { setPageSize(1) }
                PoiSearch(context, query).apply {
                    setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                        override fun onPoiSearched(result: PoiResult?, responseCode: Int) {
                            if (continuation.isActive) continuation.resume(responseCode == AMAP_SUCCESS_CODE)
                        }

                        override fun onPoiItemSearched(item: PoiItem?, responseCode: Int) = Unit
                    })
                    searchPOIAsyn()
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
    } ?: false
}
