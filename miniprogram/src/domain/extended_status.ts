import type { CarStatus } from '../services/types'

export type MediaPlayback = {
  title: string
  artist: string | null
  displayText: string
}

export function mediaPlayback(status: CarStatus | null | undefined): MediaPlayback | null {
  if (!status?.mediaInfo) return null
  const title = status.mediaInfo.title?.trim()
  if (!title) return null
  const artist = status.mediaInfo.artist?.trim() || null
  const displayText = artist ? `${title} — ${artist}` : title
  return { title, artist, displayText }
}

export type SoftwareUpdateUi = {
  phase: 'downloading' | 'installing'
  percent: number | null
}

export function softwareUpdateUi(status: CarStatus | null | undefined): SoftwareUpdateUi | null {
  const update = status?.softwareUpdate
  if (!update || !update.status) return null
  const s = update.status.trim().toLowerCase()
  if (s === 'downloading') {
    const p = update.downloadPercent
    return {
      phase: 'downloading',
      percent: typeof p === 'number' && Number.isFinite(p) ? Math.max(0, Math.min(100, Math.round(p))) : null,
    }
  }
  if (s === 'installing') {
    const p = update.installPercent
    return {
      phase: 'installing',
      percent: typeof p === 'number' && Number.isFinite(p) ? Math.max(0, Math.min(100, Math.round(p))) : null,
    }
  }
  return null
}

export type ScheduledEvent = {
  kind: 'charging_start' | 'departure'
  timeText: string
}

function formatClockTime(isoString: string | null | undefined): string | null {
  if (!isoString) return null
  try {
    const d = new Date(isoString)
    if (isNaN(d.getTime())) return null
    const hours = d.getHours().toString().padStart(2, '0')
    const minutes = d.getMinutes().toString().padStart(2, '0')
    return `${hours}:${minutes}`
  } catch {
    return null
  }
}

export function scheduledEvents(status: CarStatus | null | undefined): ScheduledEvent[] {
  if (!status) return []
  const events: ScheduledEvent[] = []
  const chargeTime = formatClockTime(status.scheduledChargingStartTime)
  if (chargeTime) {
    events.push({ kind: 'charging_start', timeText: chargeTime })
  }
  const departureTime = formatClockTime(status.scheduledDepartureTime)
  if (departureTime) {
    events.push({ kind: 'departure', timeText: departureTime })
  }
  return events
}

export type VehicleModes = {
  valetOn: boolean
  speedLimitKmh: number | null
}

export function vehicleModes(status: CarStatus | null | undefined): VehicleModes | null {
  if (!status) return null
  const valetOn = status.valetMode === true
  const limit = status.speedLimitMode?.active === true && typeof status.speedLimitMode.currentLimitKmh === 'number'
    ? status.speedLimitMode.currentLimitKmh
    : null
  if (!valetOn && limit === null) return null
  return { valetOn, speedLimitKmh: limit }
}

export type ClimateTargets = {
  driver: number | null
  passenger: number | null
}

export function climateTargets(status: CarStatus | null | undefined): ClimateTargets | null {
  if (!status) return null
  const driver = typeof status.driverTempSetting === 'number' ? status.driverTempSetting : null
  const passenger = typeof status.passengerTempSetting === 'number' ? status.passengerTempSetting : null
  if (driver === null && passenger === null) return null
  return { driver, passenger }
}
