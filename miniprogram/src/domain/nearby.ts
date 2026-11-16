import type { ChargingSite } from '../services/types'

/**
 * Nearby-charging display derivations, aligned with the Android App
 * (NearbyChargingModels.kt): the backend may omit price_text /
 * parking_policy per site, in which case the App falls back to a
 * type-based default instead of hiding the price/parking row.
 */

export function isSuperchargerSite(site: ChargingSite): boolean {
  const name = site.name ?? ''
  return site.type === 'supercharger' || name.includes('超级充电') || name.includes('超充') || name.includes('Tesla')
}

/** Amap POIs are generic charging places: never dress them as Tesla sites. */
export function isAmapPoiSite(site: ChargingSite): boolean {
  return site.type === 'poi'
}

/** "3/8" style stall occupancy for sites reporting counts, else null. */
export function siteStallsText(site: ChargingSite): string | null {
  if (site.available_stalls != null && site.total_stalls != null) {
    return `${site.available_stalls}/${site.total_stalls}`
  }
  return null
}

export function siteDisplayPrice(site: ChargingSite): string {
  if (site.price_text) return site.price_text
  if (isAmapPoiSite(site)) return '价格未提供'
  return isSuperchargerSite(site) ? '¥1.25/度 (分时)' : '¥1.15/度'
}

export function siteDisplayParking(site: ChargingSite): string {
  if (site.parking_policy) return site.parking_policy
  if (isAmapPoiSite(site)) return '停车信息未提供'
  return isSuperchargerSite(site) ? '充电免费停车2小时' : '前2小时免费停车'
}
