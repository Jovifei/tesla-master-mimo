import type { Drive } from '../services/types'
import { formatRouteAddresses, toChineseDisplayAddress } from './address_display'

/**
 * Dashboard "最近行程" card model + location-card text, aligned with the
 * Android App (DashboardRecentDriveCard / VehicleStatusPresentation
 * .locationDisplay): geofence name wins, then an address. The
 * miniprogram has no client reverse-geocoder, so the address step uses
 * the latest drive's end address — but only when the car is still
 * within 300 m of that drive's end point, i.e. it has not moved since
 * the trip ended. The final fallback keeps the miniprogram's concise
 * map affordance instead of the App's raw-coordinates branch (the
 * mini's location card deliberately leaves coordinates to the map page).
 */

export type RecentDriveCardModel = {
  routeText: string
  endTimeText: string
  distanceText: string
  socRangeText: string | null
  capsules: { label: string; value: string }[]
}

const UNKNOWN = '未知'

function formatEndTime(endDate: string | null): string {
  if (!endDate) return ''
  const parsed = new Date(endDate)
  if (Number.isNaN(parsed.getTime())) return endDate.slice(0, 16).replace('T', ' ')
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
}

export function recentDriveCardModel(drive: Drive): RecentDriveCardModel {
  const [startText, endText] = formatRouteAddresses(drive.startAddress, drive.endAddress, UNKNOWN)
  const routeText =
    startText === UNKNOWN && endText === UNKNOWN
      ? UNKNOWN
      : startText === UNKNOWN
        ? endText
        : endText === UNKNOWN || startText === endText
          ? startText
          : `${startText} → ${endText}`

  const distanceText =
    drive.distanceKm != null && drive.distanceKm >= 0 ? `${drive.distanceKm.toFixed(1)} km` : '--'
  const durationText =
    drive.durationMinutes != null && drive.durationMinutes >= 0
      ? `${Math.round(drive.durationMinutes)} min`
      : '--'
  const efficiencyText =
    drive.efficiencyWhKm != null
      ? `${Math.round(drive.efficiencyWhKm)} Wh/km`
      : drive.energyConsumedKwh != null
        ? `${drive.energyConsumedKwh.toFixed(1)} kWh`
        : '--'
  const speedText =
    drive.speedMaxKmh != null && drive.speedMaxKmh >= 0 ? `${Math.round(drive.speedMaxKmh)} km/h` : '--'

  const startSoc = drive.startBatteryLevel != null && drive.startBatteryLevel > 0 ? drive.startBatteryLevel : null
  const endSoc = drive.endBatteryLevel != null && drive.endBatteryLevel > 0 ? drive.endBatteryLevel : null
  const socRangeText =
    startSoc != null && endSoc != null
      ? `${startSoc}% → ${endSoc}%`
      : startSoc != null
        ? `${startSoc}%`
        : endSoc != null
          ? `${endSoc}%`
          : null

  return {
    routeText,
    endTimeText: formatEndTime(drive.endDate),
    distanceText,
    socRangeText,
    capsules: [
      { label: '能耗', value: efficiencyText },
      { label: '里程', value: distanceText },
      { label: '时长', value: durationText },
      { label: '极速', value: speedText },
    ],
  }
}

function haversineKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const rad = (deg: number) => (deg * Math.PI) / 180
  const dLat = rad(bLat - aLat)
  const dLng = rad(bLng - aLng)
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLng / 2) ** 2
  return 6371 * 2 * Math.asin(Math.sqrt(h))
}

export function locationDisplayText(input: {
  geofence?: string | null
  location?: { latitude: number; longitude: number } | null
  recentDrive?: Drive | null
}): string | null {
  const geofence = input.geofence?.trim()
  if (geofence) return geofence
  const location = input.location
  if (!location) return null
  const drive = input.recentDrive
  if (
    drive?.endAddress &&
    drive.endLatitude != null &&
    drive.endLongitude != null &&
    haversineKm(location.latitude, location.longitude, drive.endLatitude, drive.endLongitude) <= 0.3
  ) {
    const address = toChineseDisplayAddress(drive.endAddress) ?? drive.endAddress.trim()
    if (address) return address
  }
  return '已获取位置 · 点击查看地图'
}
