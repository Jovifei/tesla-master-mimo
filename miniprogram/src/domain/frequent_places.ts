import { isHistoryQuarantined } from './history'
import { toChineseDisplayAddress } from './address_display'
import type { Drive } from '../services/types'

/**
 * "我的地点" has no server-side rows on this deployment yet. Instead of an
 * empty card, derive frequent places from the user's own completed drives.
 * Only addresses the drive actually reported count; unknown placeholders are
 * never promoted into a place. A place can later be saved explicitly as a
 * vehicle place when a drive supplied coordinates.
 */

export type FrequentPlace = {
  name: string
  count: number
  latitude: number | null
  longitude: number | null
  lastSeen: string | null
}

function validCoordinate(latitude: number | null | undefined, longitude: number | null | undefined): boolean {
  return latitude != null && longitude != null && Number.isFinite(latitude) && Number.isFinite(longitude) &&
    latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180 && (latitude !== 0 || longitude !== 0)
}

function placeName(value: string | null | undefined): string | null {
  const display = toChineseDisplayAddress(value ?? '')
  const name = (display ?? '').trim()
  if (!name || name === '未知地点' || name === '暂无数据') return null
  return name
}

export function frequentPlaces(drives: readonly Drive[], limit = 5): FrequentPlace[] {
  const places = new Map<string, FrequentPlace>()
  const collect = (address: string | null | undefined, latitude: number | null | undefined, longitude: number | null | undefined, at: string | null | undefined) => {
    const name = placeName(address)
    if (!name) return
    const time = at ? Date.parse(at) : NaN
    const seen = time
    const previous = places.get(name)
    if (!previous) {
      places.set(name, {
        name,
        count: 1,
        latitude: validCoordinate(latitude, longitude) ? latitude! : null,
        longitude: validCoordinate(latitude, longitude) ? longitude! : null,
        lastSeen: Number.isFinite(seen) ? new Date(seen).toISOString() : null,
      })
      return
    }
    previous.count += 1
    const previousSeen = previous.lastSeen ? Date.parse(previous.lastSeen) : NaN
    if (Number.isFinite(seen) && (!Number.isFinite(previousSeen) || seen > previousSeen)) {
      previous.lastSeen = new Date(seen).toISOString()
      if (validCoordinate(latitude, longitude)) {
        previous.latitude = latitude!
        previous.longitude = longitude!
      }
    } else if (previous.latitude == null && validCoordinate(latitude, longitude)) {
      previous.latitude = latitude!
      previous.longitude = longitude!
    }
  }
  for (const drive of drives) {
    if (isHistoryQuarantined(drive)) continue
    collect(drive.startAddress, drive.startLatitude, drive.startLongitude, drive.startDate)
    collect(drive.endAddress, drive.endLatitude, drive.endLongitude, drive.endDate)
  }
  return [...places.values()]
    .sort((left, right) => right.count - left.count || (Date.parse(right.lastSeen ?? '') || 0) - (Date.parse(left.lastSeen ?? '') || 0))
    .slice(0, Math.max(0, Math.min(20, limit)))
}