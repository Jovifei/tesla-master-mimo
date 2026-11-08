import type { WebChargingSite } from './matelink-types'

/**
 * Nearby-charging display derivations, ported from the miniprogram's
 * domain/nearby.ts (App parity): when the backend omits price or
 * parking text for a site, fall back to the type-based default the
 * App uses instead of hiding the row.
 */

export function isSuperchargerSite(site: WebChargingSite): boolean {
  const name = site.name ?? ''
  return site.type === 'supercharger' || name.includes('超级充电') || name.includes('超充') || name.includes('Tesla')
}

export function siteStallsText(site: WebChargingSite): string | null {
  if (site.availableStalls != null && site.totalStalls != null) {
    return `${site.availableStalls}/${site.totalStalls}`
  }
  return null
}

export function siteDisplayPrice(site: WebChargingSite): string {
  if (site.priceText) return site.priceText
  return isSuperchargerSite(site) ? '¥1.25/度 (分时)' : '¥1.15/度'
}

export function siteDisplayParking(site: WebChargingSite): string {
  if (site.parkingPolicy) return site.parkingPolicy
  return isSuperchargerSite(site) ? '充电免费停车2小时' : '前2小时免费停车'
}

/** AMap marker page for one site (no JS key required for the link-out). */
export function amapMarkerUrl(site: WebChargingSite): string | null {
  if (site.latitude == null || site.longitude == null) return null
  const name = encodeURIComponent(site.name ?? '充电站')
  return `https://uri.amap.com/marker?position=${site.longitude},${site.latitude}&name=${name}&coordinate=gaode`
}

export function amapCoordinateUrl(latitude: number, longitude: number, name: string): string {
  return `https://uri.amap.com/marker?position=${longitude},${latitude}&name=${encodeURIComponent(name)}&coordinate=gaode`
}
