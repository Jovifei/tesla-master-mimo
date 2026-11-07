package com.matelink.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.matelink.data.local.AmapSettings
import com.matelink.data.local.AmapSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ChineseLocationAvailability {
    READY,
    KEY_NOT_CONFIGURED,
    PRIVACY_NOT_ACCEPTED,
    RESTART_REQUIRED
}

internal fun chineseLocationAvailability(settings: AmapSettings): ChineseLocationAvailability = when {
    !settings.hasKey -> ChineseLocationAvailability.KEY_NOT_CONFIGURED
    !settings.privacyAgreed -> ChineseLocationAvailability.PRIVACY_NOT_ACCEPTED
    settings.restartRequired -> ChineseLocationAvailability.RESTART_REQUIRED
    else -> ChineseLocationAvailability.READY
}

data class ChineseLocation(
    val address: String,
    val countryCode: String?,
    val countryName: String?,
    val regionName: String?,
    val city: String?
)

@Singleton
class AmapReverseGeocoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: AmapSettingsStore
) {
    companion object {
        private const val TAG = "AmapReverseGeocoder"
        private val memoryCache = ConcurrentHashMap<String, ChineseLocation>()
        private val queryMutex = Mutex()
        @Volatile private var lastQueryTime = 0L

        fun getCached(latitude: Double?, longitude: Double?): ChineseLocation? {
            if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
                (latitude == 0.0 && longitude == 0.0)) return null
            val cacheKey = "%.4f,%.4f".format(latitude, longitude)
            return memoryCache[cacheKey]
        }

        fun putCache(latitude: Double, longitude: Double, location: ChineseLocation) {
            val cacheKey = "%.4f,%.4f".format(latitude, longitude)
            memoryCache[cacheKey] = location
        }
    }

    val availability: Flow<ChineseLocationAvailability> = settingsStore.settings
        .map(::chineseLocationAvailability)
        .distinctUntilChanged()

    suspend fun currentAvailability(): ChineseLocationAvailability = availability.first()

    suspend fun reverse(latitude: Double, longitude: Double): ChineseLocation? {
        val currentAvail = currentAvailability()
        if (currentAvail != ChineseLocationAvailability.READY) {
            Log.d(TAG, "Reverse geocoding skipped: availability is $currentAvail")
            return null
        }
        if (!latitude.isFinite() || !longitude.isFinite() || (latitude == 0.0 && longitude == 0.0)) {
            return null
        }

        // Cache precision: ~11m (4 decimals)
        val cacheKey = "%.4f,%.4f".format(latitude, longitude)
        memoryCache[cacheKey]?.let { return it }

        val key = settingsStore.currentKey().trim().takeIf { it.isNotBlank() } ?: return null
        val coordinate = AmapCoordinateTransformer.normalize(latitude, longitude) ?: return null

        return withContext(Dispatchers.IO) {
            queryMutex.withLock {
                memoryCache[cacheKey]?.let { return@withLock it }

                // Rate limiting: wait at least 700ms between calls to avoid CUQPS_HAS_EXCEEDED_THE_LIMIT
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastQueryTime
                if (elapsed in 0..700L) {
                    delay(700L - elapsed)
                }

                fun doQuery(): ChineseLocation? {
                    MapsInitializer.updatePrivacyShow(context, true, true)
                    MapsInitializer.updatePrivacyAgree(context, true)
                    MapsInitializer.setApiKey(key)
                    ServiceSettings.updatePrivacyShow(context, true, true)
                    ServiceSettings.updatePrivacyAgree(context, true)
                    ServiceSettings.getInstance().setApiKey(key)

                    val result = GeocodeSearch(context).getFromLocation(
                        RegeocodeQuery(LatLonPoint(coordinate.latitude, coordinate.longitude), 200f, GeocodeSearch.AMAP)
                    ) ?: return null

                    val address = result.formatAddress?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return null
                    val region = listOfNotNull(
                        result.province?.trim()?.takeIf { it.isNotEmpty() },
                        result.district?.trim()?.takeIf { it.isNotEmpty() }
                    ).joinToString(" ").takeIf { it.isNotEmpty() }
                    return ChineseLocation(
                        address = address,
                        countryCode = result.countryCode?.trim()?.takeIf { it.isNotEmpty() } ?: "CN",
                        countryName = result.country?.trim()?.takeIf { it.isNotEmpty() } ?: "中国",
                        regionName = region,
                        city = result.city?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }

                var location: ChineseLocation? = null
                try {
                    lastQueryTime = SystemClock.elapsedRealtime()
                    location = doQuery()
                } catch (e: Exception) {
                    Log.w(TAG, "Amap reverse geocoding failed: ${e.message ?: e.javaClass.simpleName}")
                    if (e.message?.contains("CUQPS") == true || e.javaClass.simpleName.contains("AMapException")) {
                        delay(1200L)
                        lastQueryTime = SystemClock.elapsedRealtime()
                        location = runCatching { doQuery() }.getOrNull()
                    }
                }

                location?.also {
                    Log.i(TAG, "Amap reverse geocoded ($latitude, $longitude) -> ${it.address}")
                    memoryCache[cacheKey] = it
                }
            }
        }
    }
}
