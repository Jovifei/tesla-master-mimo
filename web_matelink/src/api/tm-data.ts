import type { WebCharge, WebChargePoint, WebDriveSummary } from './matelink-types'

/**
 * Derivations for the TeslaMate panel reproduction (docs/TESLAMATE-MAPPING.md).
 * Everything here is computed from records the backend already stores —
 * no invented series. Where TeslaMate relies on a continuously sampled
 * series we do not have (SOC history, odometer log), the derivation is a
 * stitch over session boundaries and is labelled as such in the UI.
 */

const notQuarantined = (row: { qualityState: string | null }): boolean => row.qualityState !== 'quarantined'

const ms = (iso: string | null): number | null => {
  if (!iso) return null
  const t = Date.parse(iso)
  return Number.isFinite(t) ? t : null
}

/* ------------------------- SOC history stitch ------------------------ */

export type SocSegment = {
  kind: 'charge' | 'drive'
  /** Boundary-only segments (drives) are approximations and render dashed. */
  approx: boolean
  points: { t: number; soc: number }[]
}

/**
 * SOC history assembled from session boundaries: charges contribute
 * their real in-session samples when the caller fetched them (keyed by
 * charge id), drives contribute start/end boundary points only. Parked
 * intervals produce no segment, so the line breaks while parked —
 * exactly the 口径 TeslaMate's sampled curve would show as sleep gaps.
 */
export function socSegments(
  charges: readonly WebCharge[],
  drives: readonly WebDriveSummary[],
  chargePoints: ReadonlyMap<string, readonly WebChargePoint[]>,
  windowStart: number,
  windowEnd: number
): SocSegment[] {
  const segments: SocSegment[] = []
  const inWindow = (t: number) => t >= windowStart && t <= windowEnd
  for (const charge of charges) {
    if (!notQuarantined(charge)) continue
    const start = ms(charge.startDate)
    const end = ms(charge.endDate)
    if (start == null || end == null) continue
    const sampled = (chargePoints.get(charge.id) ?? [])
      .filter(p => p.batteryLevel != null)
      .map(p => ({ t: Date.parse(p.date), soc: p.batteryLevel as number }))
      .filter(p => Number.isFinite(p.t) && inWindow(p.t))
    if (sampled.length >= 2) {
      segments.push({ kind: 'charge', approx: false, points: sampled })
      continue
    }
    const points: { t: number; soc: number }[] = []
    if (charge.startBatteryLevel != null && inWindow(start)) points.push({ t: start, soc: charge.startBatteryLevel })
    if (charge.endBatteryLevel != null && inWindow(end)) points.push({ t: end, soc: charge.endBatteryLevel })
    if (points.length >= 2) segments.push({ kind: 'charge', approx: true, points })
  }
  for (const drive of drives) {
    if (!notQuarantined(drive)) continue
    const start = ms(drive.startDate)
    const end = ms(drive.endDate)
    if (start == null || end == null) continue
    const points: { t: number; soc: number }[] = []
    if (drive.startBatteryLevel != null && inWindow(start)) points.push({ t: start, soc: drive.startBatteryLevel })
    if (drive.endBatteryLevel != null && inWindow(end)) points.push({ t: end, soc: drive.endBatteryLevel })
    if (points.length >= 2) segments.push({ kind: 'drive', approx: true, points })
  }
  segments.sort((a, b) => (a.points[0]?.t ?? 0) - (b.points[0]?.t ?? 0))
  return segments
}

/* --------------------------- mileage stitch -------------------------- */

export type TimedValue = { t: number; value: number }

/** Odometer history stitched from per-session odometer start/end. */
export function mileageSeries(drives: readonly WebDriveSummary[]): TimedValue[] {
  const points: TimedValue[] = []
  for (const drive of drives) {
    if (!notQuarantined(drive)) continue
    const start = ms(drive.startDate)
    const end = ms(drive.endDate)
    if (start != null && drive.odometerStartKm != null) points.push({ t: start, value: drive.odometerStartKm })
    if (end != null && drive.odometerEndKm != null) points.push({ t: end, value: drive.odometerEndKm })
  }
  points.sort((a, b) => a.t - b.t)
  // Consecutive equal odometer readings carry no new information (the
  // car did not move); keep the first so the stitched line stays honest.
  return points.filter((p, i) => i === 0 || p.value !== points[i - 1].value)
}

/* ----------------------------- parked gaps --------------------------- */

export type ParkedGap = {
  start: number
  end: number
  durationMinutes: number
  startSoc: number | null
  endSoc: number | null
  /** endSoc − startSoc (negative while parked); null when either side is unknown. */
  deltaSoc: number | null
  address: string | null
}

type SessionLike = {
  start: number
  end: number
  startSoc: number | null
  endSoc: number | null
  endAddress: string | null
  startAddress: string | null
}

/**
 * Parked intervals between consecutive sessions (drives and charges),
 * with the SOC delta across the gap. Energy is deliberately absent:
 * for native cars the backend publishes no qualified parked-energy
 * figure, so the Vampire Drain page shows 电量差 and marks 耗电量待补.
 */
export function parkedGaps(
  drives: readonly WebDriveSummary[],
  charges: readonly WebCharge[],
  minMinutes = 5
): ParkedGap[] {
  const sessions: SessionLike[] = []
  for (const drive of drives) {
    if (!notQuarantined(drive)) continue
    const start = ms(drive.startDate)
    const end = ms(drive.endDate)
    if (start != null && end != null && end > start)
      sessions.push({ start, end, startSoc: drive.startBatteryLevel, endSoc: drive.endBatteryLevel, endAddress: drive.endAddress, startAddress: drive.startAddress })
  }
  for (const charge of charges) {
    if (!notQuarantined(charge)) continue
    const start = ms(charge.startDate)
    const end = ms(charge.endDate)
    if (start != null && end != null && end > start)
      sessions.push({ start, end, startSoc: charge.startBatteryLevel, endSoc: charge.endBatteryLevel, endAddress: charge.address, startAddress: charge.address })
  }
  sessions.sort((a, b) => a.start - b.start)
  const gaps: ParkedGap[] = []
  for (let i = 0; i + 1 < sessions.length; i++) {
    const prev = sessions[i]
    const next = sessions[i + 1]
    if (next.start <= prev.end) continue
    const durationMinutes = (next.start - prev.end) / 60000
    if (durationMinutes < minMinutes) continue
    gaps.push({
      start: prev.end,
      end: next.start,
      durationMinutes,
      startSoc: prev.endSoc,
      endSoc: next.startSoc,
      deltaSoc: prev.endSoc != null && next.startSoc != null ? next.startSoc - prev.endSoc : null,
      address: next.startAddress ?? prev.endAddress,
    })
  }
  return gaps
}

/* ---------------------------- period stats --------------------------- */

export type PeriodRow = {
  key: string
  label: string
  start: number
  distanceKm: number | null
  driveCount: number
  chargedKwh: number | null
  chargeCount: number
  cost: number | null
}

function periodKeyOf(date: Date, granularity: 'day' | 'week' | 'month'): { key: string; label: string; start: number } {
  const y = date.getFullYear()
  const m = date.getMonth()
  const d = date.getDate()
  if (granularity === 'month') {
    return { key: `${y}-${m}`, label: `${y}年${m + 1}月`, start: new Date(y, m, 1).getTime() }
  }
  if (granularity === 'week') {
    const monday = new Date(y, m, d - ((date.getDay() + 6) % 7))
    return {
      key: `${monday.getFullYear()}-${monday.getMonth()}-${monday.getDate()}`,
      label: `${monday.getMonth() + 1}月${monday.getDate()}日当周`,
      start: monday.getTime(),
    }
  }
  return { key: `${y}-${m}-${d}`, label: `${y}年${m + 1}月${d}日`, start: new Date(y, m, d).getTime() }
}

/** Statistics-page table: per day/week/month aggregates (quarantined excluded). */
export function periodStats(
  drives: readonly WebDriveSummary[],
  charges: readonly WebCharge[],
  granularity: 'day' | 'week' | 'month'
): PeriodRow[] {
  const rows = new Map<string, PeriodRow & { distanceKnown: boolean; chargedKnown: boolean; costKnown: boolean }>()
  const bucket = (iso: string | null) => {
    const t = ms(iso)
    if (t == null) return null
    const { key, label, start } = periodKeyOf(new Date(t), granularity)
    let row = rows.get(key)
    if (!row) {
      row = { key, label, start, distanceKm: null, driveCount: 0, chargedKwh: null, chargeCount: 0, cost: null, distanceKnown: false, chargedKnown: false, costKnown: false }
      rows.set(key, row)
    }
    return row
  }
  for (const drive of drives) {
    if (!notQuarantined(drive)) continue
    const row = bucket(drive.startDate)
    if (!row) continue
    row.driveCount++
    if (drive.distanceKm != null) {
      row.distanceKm = (row.distanceKm ?? 0) + drive.distanceKm
      row.distanceKnown = true
    }
  }
  for (const charge of charges) {
    if (!notQuarantined(charge)) continue
    const row = bucket(charge.startDate)
    if (!row) continue
    row.chargeCount++
    if (charge.energyAddedKwh != null) {
      row.chargedKwh = (row.chargedKwh ?? 0) + charge.energyAddedKwh
      row.chargedKnown = true
    }
    if (charge.cost != null) {
      row.cost = (row.cost ?? 0) + charge.cost
      row.costKnown = true
    }
  }
  return [...rows.values()]
    .sort((a, b) => b.start - a.start)
    .map(({ distanceKnown, chargedKnown, costKnown, ...row }) => ({
      ...row,
      distanceKm: distanceKnown ? row.distanceKm : null,
      chargedKwh: chargedKnown ? row.chargedKwh : null,
      cost: costKnown ? row.cost : null,
    }))
}

/* ---------------------------- charge heatmap -------------------------- */

export type HeatCell = { count: number; kwh: number }

/** 7 (Mon..Sun) × 24 heat matrix of charge starts. */
export function chargeHeatmap(charges: readonly WebCharge[]): HeatCell[][] {
  const grid: HeatCell[][] = Array.from({ length: 7 }, () => Array.from({ length: 24 }, () => ({ count: 0, kwh: 0 })))
  for (const charge of charges) {
    if (!notQuarantined(charge)) continue
    const t = ms(charge.startDate)
    if (t == null) continue
    const date = new Date(t)
    const row = grid[(date.getDay() + 6) % 7]
    const cell = row[date.getHours()]
    cell.count++
    if (charge.energyAddedKwh != null) cell.kwh += charge.energyAddedKwh
  }
  return grid
}

/* ------------------------- temperature buckets ------------------------ */

export type TempBucket = { label: string; driveCount: number; avgConsumptionWhPerKm: number | null; distanceKm: number }

const TEMP_RANGES: { label: string; min: number; max: number }[] = [
  { label: '低于 -10°C', min: -Infinity, max: -10 },
  { label: '-10 ~ 0°C', min: -10, max: 0 },
  { label: '0 ~ 10°C', min: 0, max: 10 },
  { label: '10 ~ 20°C', min: 10, max: 20 },
  { label: '20 ~ 30°C', min: 20, max: 30 },
  { label: '30°C 以上', min: 30, max: Infinity },
]

/** Temperature – efficiency table: only drives carrying a consumption figure contribute to the average. */
export function temperatureBuckets(drives: readonly WebDriveSummary[]): TempBucket[] {
  return TEMP_RANGES.map(range => {
    let count = 0
    let weighted = 0
    let weight = 0
    let distance = 0
    for (const drive of drives) {
      if (!notQuarantined(drive) || drive.outsideTempAvgC == null) continue
      if (drive.outsideTempAvgC < range.min || drive.outsideTempAvgC >= range.max) continue
      count++
      if (drive.distanceKm != null) distance += drive.distanceKm
      if (drive.consumptionWhPerKm != null && drive.distanceKm != null && drive.distanceKm > 0) {
        weighted += drive.consumptionWhPerKm * drive.distanceKm
        weight += drive.distanceKm
      }
    }
    return { label: range.label, driveCount: count, avgConsumptionWhPerKm: weight > 0 ? weighted / weight : null, distanceKm: distance }
  })
}

/* --------------------------- destinations ----------------------------- */

export type DestinationStat = { address: string; count: number; distanceKm: number }

export function topDestinations(drives: readonly WebDriveSummary[], limit = 10): DestinationStat[] {
  const map = new Map<string, DestinationStat>()
  for (const drive of drives) {
    if (!notQuarantined(drive) || !drive.endAddress) continue
    const entry = map.get(drive.endAddress) ?? { address: drive.endAddress, count: 0, distanceKm: 0 }
    entry.count++
    if (drive.distanceKm != null) entry.distanceKm += drive.distanceKm
    map.set(drive.endAddress, entry)
  }
  return [...map.values()].sort((a, b) => b.count - a.count).slice(0, limit)
}

export type AddressStat = { address: string; visits: number; lastAt: number }

export function addressStats(drives: readonly WebDriveSummary[], charges: readonly WebCharge[]): AddressStat[] {
  const map = new Map<string, AddressStat>()
  const visit = (address: string | null, iso: string | null) => {
    if (!address) return
    const t = ms(iso) ?? 0
    const entry = map.get(address) ?? { address, visits: 0, lastAt: 0 }
    entry.visits++
    if (t > entry.lastAt) entry.lastAt = t
    map.set(address, entry)
  }
  for (const drive of drives) {
    if (!notQuarantined(drive)) continue
    visit(drive.startAddress, drive.startDate)
    visit(drive.endAddress, drive.endDate)
  }
  for (const charge of charges) {
    if (!notQuarantined(charge)) continue
    visit(charge.address, charge.startDate)
  }
  return [...map.values()].sort((a, b) => b.lastAt - a.lastAt)
}

/* --------------------------- speed histogram --------------------------- */

export type SpeedBucket = { label: string; count: number }

export function speedHistogram(speeds: readonly number[]): SpeedBucket[] {
  const edges = [0, 20, 40, 60, 80, 100, 120, 140, Infinity]
  const buckets: SpeedBucket[] = []
  for (let i = 0; i + 1 < edges.length; i++) {
    const label = edges[i + 1] === Infinity ? '140 以上' : `${edges[i]}–${edges[i + 1]}`
    buckets.push({ label, count: speeds.filter(s => s >= edges[i] && s < edges[i + 1]).length })
  }
  return buckets
}

/* ------------------------------ timeline ------------------------------ */

export type TimelineEntry = {
  kind: 'drive' | 'charge'
  id: string
  start: number
  end: number | null
  title: string
  subtitle: string
}

export function sessionTimeline(drives: readonly WebDriveSummary[], charges: readonly WebCharge[]): TimelineEntry[] {
  const entries: TimelineEntry[] = []
  for (const drive of drives) {
    const start = ms(drive.startDate)
    if (start == null) continue
    entries.push({
      kind: 'drive',
      id: drive.id,
      start,
      end: ms(drive.endDate),
      title: `${drive.startAddress ?? '未知地点'} → ${drive.endAddress ?? '未知地点'}`,
      subtitle: drive.distanceKm != null ? `行驶 ${drive.distanceKm.toFixed(1)} km` : '行驶',
    })
  }
  for (const charge of charges) {
    const start = ms(charge.startDate)
    if (start == null) continue
    entries.push({
      kind: 'charge',
      id: charge.id,
      start,
      end: ms(charge.endDate),
      title: charge.address ?? '未知充电地点',
      subtitle: charge.energyAddedKwh != null ? `充入 ${charge.energyAddedKwh.toFixed(1)} kWh` : '充电',
    })
  }
  return entries.sort((a, b) => b.start - a.start)
}

/** Daily outside-temperature envelope from per-drive averages （口径： each point is one drive's mean). */
export function dailyTempEnvelope(drives: readonly WebDriveSummary[]): { day: string; t: number; min: number; max: number }[] {
  const byDay = new Map<string, { t: number; values: number[] }>()
  for (const drive of drives) {
    if (!notQuarantined(drive) || drive.outsideTempAvgC == null) continue
    const t = ms(drive.startDate)
    if (t == null) continue
    const date = new Date(t)
    const key = `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
    const entry = byDay.get(key) ?? { t: new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime(), values: [] }
    entry.values.push(drive.outsideTempAvgC)
    byDay.set(key, entry)
  }
  return [...byDay.entries()]
    .map(([day, entry]) => ({ day, t: entry.t, min: Math.min(...entry.values), max: Math.max(...entry.values) }))
    .sort((a, b) => a.t - b.t)
}
