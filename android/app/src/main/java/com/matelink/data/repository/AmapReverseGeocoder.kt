package com.matelink.data.repository

import android.content.Context
import android.util.Log
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.matelink.data.local.AmapSettings
import com.matelink.data.local.AmapSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    settings.restartRequired || !settings.mapLoaded -> ChineseLocationAvailability.RESTART_REQUIRED
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

        val key = settingsStore.currentKey().trim().takeIf { it.isNotBlank() } ?: return null

        return withContext(Dispatchers.IO) {
            runCatching {
                MapsInitializer.updatePrivacyShow(context, true, true)
                MapsInitializer.updatePrivacyAgree(context, true)
                MapsInitializer.setApiKey(key)
                ServiceSettings.updatePrivacyShow(context, true, true)
                ServiceSettings.updatePrivacyAgree(context, true)
                ServiceSettings.getInstance().setApiKey(key)

                val result = GeocodeSearch(context).getFromLocation(
                    RegeocodeQuery(LatLonPoint(latitude, longitude), 200f, GeocodeSearch.AMAP)
                ) ?: return@runCatching null

                val address = result.formatAddress?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@runCatching null
                val region = listOfNotNull(
                    result.province?.trim()?.takeIf { it.isNotEmpty() },
                    result.district?.trim()?.takeIf { it.isNotEmpty() }
                ).joinToString(" ").takeIf { it.isNotEmpty() }
                ChineseLocation(
                    address = address,
                    countryCode = result.countryCode?.trim()?.takeIf { it.isNotEmpty() } ?: "CN",
                    countryName = result.country?.trim()?.takeIf { it.isNotEmpty() } ?: "中国",
                    regionName = region,
                    city = result.city?.trim()?.takeIf { it.isNotEmpty() }
                ).also {
                    Log.i(TAG, "Successfully reverse-geocoded ($latitude, $longitude) -> ${it.address}")
                }
            }.onFailure { e ->
                Log.w(TAG, "Amap reverse geocoding failed for ($latitude, $longitude): ${e.message}", e)
            }.getOrNull()
        }
    }
}
