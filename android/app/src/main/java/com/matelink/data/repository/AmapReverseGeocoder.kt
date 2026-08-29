package com.matelink.data.repository

import android.content.Context
import com.amap.api.services.core.LatLonPoint
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
    val availability: Flow<ChineseLocationAvailability> = settingsStore.settings
        .map(::chineseLocationAvailability)
        .distinctUntilChanged()

    suspend fun currentAvailability(): ChineseLocationAvailability = availability.first()

    suspend fun reverse(latitude: Double, longitude: Double): ChineseLocation? {
        if (currentAvailability() != ChineseLocationAvailability.READY) return null
        if (!latitude.isFinite() || !longitude.isFinite()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val result = GeocodeSearch(context).getFromLocation(
                    RegeocodeQuery(LatLonPoint(latitude, longitude), 200f, GeocodeSearch.AMAP)
                )
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
                )
            }.getOrNull()
        }
    }
}
