import { isHistoryQuarantined } from './history'

/**
 * Daily battery-level curve for the dashboard, aligned with the Android App's
 * inline history entry. The App shows the recorded battery history without
 * sending the user to the statistics screen, so the miniprogram takes one
 * honest point per day: the last completed drive's ending SOC. Days without a
 * recorded drive stay empty; a curve is never invented from the live status.
 *
 * A drive qualifies when it is not quarantined, its reported SOC is finite and
 * inside 0..100, and its end (or start) instant falls inside the window.
 */

export type SocTrendDrive = {
  startDate?: string | null
  endDate?: string | null
  startBatteryLevel?: number | null
  endBatteryLevel?: number | null
  qualityState?: string | null
  qualityReason?: string | null
}

export type SocTrendPoint = {
  /** ISO instant of the drive endpoint that supplied this value. */
  date: string
  day: string
  soc: number
}

export type SocDailyTrendResult = {
  points: SocTrendPoint[]
  drivesInWindow: number
  /** Last point minus first point in percentage points; null with <2 points. */
  change: number | null
  latestSoc: number | null
  unavailableReason: 'no_history_in_window' | 'no_qualified_soc_samples' | null
}

const DAY_MS = 24 * 60 * 60 * 1000

function socValue(value: number | null | undefined): number | null {
  return value != null && Number.isFinite(value) && value >= 0 && value <= 100 ? value : null
}

function civilDayOf(instant: Date): string {
  const year = instant.getFullYear()
  const month = String(instant.getMonth() + 1).padStart(2, '0')
  const day = String(instant.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function socDailyTrend(
  drives: readonly SocTrendDrive[],
  windowDays: 7 | 30,
  nowMs: number
): SocDailyTrendResult {
  const from = nowMs - windowDays * DAY_MS
  let drivesInWindow = 0
  const latestByDay = new Map<string, SocTrendPoint>()
  for (const drive of drives) {
    if (isHistoryQuarantined(drive)) continue
    const end = drive.endDate ? Date.parse(drive.endDate) : NaN
    const start = drive.startDate ? Date.parse(drive.startDate) : NaN
    const at = Number.isFinite(end) ? end : start
    if (!Number.isFinite(at) || at < from || at > nowMs) continue
    drivesInWindow++
    const observed = civilDayOf(new Date(at))
    // Ending SOC is the day's last known level; start SOC is a weaker
    // fallback only when the endpoint did not carry a level.
    const endSoc = socValue(drive.endBatteryLevel)
    const startSoc = socValue(drive.startBatteryLevel)
    const soc = endSoc ?? startSoc
    if (soc == null) continue
    const previous = latestByDay.get(observed)
    if (!previous || Date.parse(previous.date) < at) {
      latestByDay.set(observed, {
        date: new Date(at).toISOString(),
        day: observed,
        soc,
      })
    }
  }
  const points = [...latestByDay.values()].sort((left, right) => Date.parse(left.date) - Date.parse(right.date))
  const change = points.length >= 2 ? points[points.length - 1]!.soc - points[0]!.soc : null
  return {
    points,
    drivesInWindow,
    change,
    latestSoc: points.length ? points[points.length - 1]!.soc : null,
    unavailableReason: points.length
      ? null
      : drivesInWindow === 0
        ? 'no_history_in_window'
        : 'no_qualified_soc_samples',
  }
}