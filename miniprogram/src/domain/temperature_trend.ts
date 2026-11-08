import { isHistoryQuarantined } from './history'

/**
 * Temperature trend points, aligned with the Android App
 * (TemperatureTrendViewModel.observedTemperaturePoints): the only
 * authority for historical temperature is the recorded drives
 * themselves. One point per drive at its start instant, using the
 * drive's reported outside temperature. The miniprogram drive
 * contract carries no per-drive inside temperature, so the series
 * is outside-only; the current inside/outside values come from the
 * live status, exactly as the App separates them.
 *
 * Admissibility mirrors the App's qualified receipt: the drive must
 * not be quarantined (or identity-ambiguous), its source must be a
 * known telemetry/archive source, and the temperature must be a
 * finite reported value.
 */

export type TemperatureTrendDrive = {
  startDate: string | null
  outsideTempC?: number | null
  source?: string | null
  qualityState?: string | null
}

export type TemperatureTrendPoint = {
  date: string
  outsideTempC: number
}

export type TemperatureTrendResult = {
  points: TemperatureTrendPoint[]
  drivesInWindow: number
  /** App historyUnavailableReason equivalents. */
  unavailableReason: 'no_history_in_window' | 'no_qualified_trip_temperatures' | null
}

const ADMISSIBLE_SOURCES = new Set(['teslamate_archive', 'fleet_api', 'telemetry_mqtt'])
const DAY_MS = 24 * 60 * 60 * 1000

export function temperatureTrendPoints(
  drives: readonly TemperatureTrendDrive[],
  windowDays: 7 | 30,
  nowMs: number
): TemperatureTrendResult {
  const from = nowMs - windowDays * DAY_MS
  let drivesInWindow = 0
  const points: TemperatureTrendPoint[] = []
  for (const drive of drives) {
    if (!drive.startDate) continue
    const at = Date.parse(drive.startDate)
    if (!Number.isFinite(at) || at < from || at > nowMs) continue
    if (isHistoryQuarantined(drive)) continue
    drivesInWindow++
    if (!drive.source || !ADMISSIBLE_SOURCES.has(drive.source)) continue
    const outside = drive.outsideTempC
    if (outside == null || !Number.isFinite(outside)) continue
    points.push({ date: drive.startDate, outsideTempC: outside })
  }
  points.sort((a, b) => Date.parse(a.date) - Date.parse(b.date))
  return {
    points,
    drivesInWindow,
    unavailableReason: points.length
      ? null
      : drivesInWindow === 0
        ? 'no_history_in_window'
        : 'no_qualified_trip_temperatures',
  }
}
