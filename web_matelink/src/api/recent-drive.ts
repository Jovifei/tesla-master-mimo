import type { WebDriveSummary } from './matelink-types'

/**
 * Recent-drive card model, mirroring the miniprogram's
 * domain/recent_drive.ts derivations (App parity): an address pair
 * collapses to a single label when both ends are the same unknown,
 * missing metrics render as '--' rather than fabricated zeros.
 */

export type RecentDriveModel = {
  route: string
  distanceText: string
  socText: string
  endText: string
  durationText: string
  energyText: string
}

const UNKNOWN = '未知地点'

function address(value: string | null): string {
  return value && value.trim() !== '' ? value.trim() : UNKNOWN
}

function formatEndTime(iso: string | null): string {
  if (!iso) return '--'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '--'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function recentDriveModel(drive: WebDriveSummary): RecentDriveModel {
  const start = address(drive.startAddress)
  const end = address(drive.endAddress)
  return {
    route: start === end ? start : `${start} → ${end}`,
    distanceText: drive.distanceKm != null ? `${drive.distanceKm.toFixed(1)} km` : '--',
    socText:
      drive.startBatteryLevel != null && drive.endBatteryLevel != null
        ? `${Math.round(drive.startBatteryLevel)}% → ${Math.round(drive.endBatteryLevel)}%`
        : '--',
    endText: formatEndTime(drive.endDate),
    durationText: drive.durationMinutes != null ? `${Math.round(drive.durationMinutes)} 分钟` : '--',
    energyText:
      drive.startBatteryLevel != null && drive.endBatteryLevel != null
        ? `${Math.max(0, drive.startBatteryLevel - drive.endBatteryLevel).toFixed(0)}% 电量`
        : '--',
  }
}
