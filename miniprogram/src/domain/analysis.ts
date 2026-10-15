import type { Charge, Drive } from '../services/types'
import { aggregateDriveHistory, isHistoryQuarantined, sortHistoryByStartDate } from './history'
import { historyDateTime } from './history_query'

export type AnalysisKind = 'stats' | 'mileage' | 'efficiency' | 'timeline' | 'vampire' | 'curves' | 'parked' | 'chargeCurves'
export const analysisTitles: Record<AnalysisKind, string> = { stats: '统计分析', mileage: '里程统计', efficiency: '能耗分析', timeline: '时间轴', vampire: '驻车损耗', curves: '行程曲线', parked: '驻车详情', chargeCurves: '充电曲线' }
export function analysisKind(value: unknown): AnalysisKind { return typeof value === 'string' && Object.prototype.hasOwnProperty.call(analysisTitles, value) ? value as AnalysisKind : 'stats' }
export function analysisDay(value: string | null | undefined): string | null { const shown = historyDateTime(value); return shown === '暂无日期' ? null : shown.slice(0, 10) }
export function analysisGroups(rows: readonly Drive[], period: 'day' | 'month' | 'year') {
  const grouped = new Map<string, Drive[]>()
  for (const row of rows) {
    const day = analysisDay(row.startDate)
    if (!day || isHistoryQuarantined(row)) continue
    const key = day.slice(0, period === 'year' ? 4 : period === 'month' ? 7 : 10)
    grouped.set(key, [...(grouped.get(key) ?? []), row])
  }
  return [...grouped].sort(([a], [b]) => a.localeCompare(b)).map(([key, items]) => ({ key, count: items.length, items, ...aggregateDriveHistory(items) }))
}
export function calendarCells(month: string, rows: readonly Drive[]) {
  if (!/^\d{4}-\d{2}$/.test(month)) return []
  const [year, number] = month.split('-').map(Number)
  if (number < 1 || number > 12 || year < 1970 || year > 9999) return []
  const groups = analysisGroups(rows, 'day')
  const leading = new Date(year, number - 1, 1).getDay()
  const count = new Date(year, number, 0).getDate()
  return Array.from({ length: leading + count }, (_, index) => {
    const day = index < leading ? null : `${month}-${String(index - leading + 1).padStart(2, '0')}`
    const actual = groups.find(group => group.key === day)
    return { day, count: actual?.count ?? 0, distance: actual?.distance ?? { value: null, known: 0, total: 0 } }
  })
}
export type ParkedWindow = { olderId: string; newerId: string; startDate: string; endDate: string; minutes: number; source: string;
  startSoc: number | null; endSoc: number | null; socDelta: number | null; energyKwh: null; chargeOverlap: boolean; chargeCoverageComplete: boolean; address: string | null }
function validSoc(value: number | null) { return value != null && Number.isFinite(value) && value >= 0 && value <= 100 ? value : null }
export function buildParkedWindows(drives: readonly Drive[], charges: readonly Charge[], chargeCoverageComplete: boolean): ParkedWindow[] {
  const ordered = sortHistoryByStartDate(drives.filter(row => !isHistoryQuarantined(row))).reverse()
  const windows: ParkedWindow[] = []
  const chargeTimesComplete = chargeCoverageComplete && charges.every(charge => !isHistoryQuarantined(charge) &&
    Number.isFinite(Date.parse(charge.startDate ?? '')) && (charge.endDate == null || Number.isFinite(Date.parse(charge.endDate))))
  for (let index = 1; index < ordered.length; index++) {
    const older = ordered[index - 1], newer = ordered[index]
    const start = Date.parse(older.endDate ?? ''), end = Date.parse(newer.startDate ?? '')
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || !older.source || older.source !== newer.source) continue
    const chargeOverlap = charges.some(charge => !isHistoryQuarantined(charge) && Date.parse(charge.startDate ?? '') < end &&
      (charge.endDate == null || Date.parse(charge.endDate) > start))
    const startSoc = validSoc(older.endBatteryLevel), endSoc = validSoc(newer.startBatteryLevel)
    windows.push({ olderId: older.id, newerId: newer.id, startDate: older.endDate!, endDate: newer.startDate!, minutes: (end - start) / 60000,
      source: older.source, startSoc, endSoc, socDelta: startSoc != null && endSoc != null ? endSoc - startSoc : null,
      energyKwh: null, chargeOverlap, chargeCoverageComplete: chargeTimesComplete, address: older.endAddress ?? newer.startAddress })
  }
  return windows.reverse()
}
function efficiency(row: Drive): number | null {
  const e = row.energy?.quality !== 'unknown' ? row.energy?.energyKwh : null, d = row.distanceKm
  const value = e != null && d != null && d > 0 ? e * 100 / d : null
  return value != null && Number.isFinite(value) ? value : null
}
export function efficiencyDistribution(rows: readonly Drive[]) {
  const eligible = rows.filter(row => !isHistoryQuarantined(row) && efficiency(row) != null)
  const values = eligible.map(row => efficiency(row)!).sort((a, b) => a - b)
  const median = values.length ? values.length % 2 ? values[Math.floor(values.length / 2)] : (values[values.length / 2 - 1] + values[values.length / 2]) / 2 : null
  return { count: values.length, min: values[0] ?? null, median, max: values[values.length - 1] ?? null,
    trips: eligible.map(row => ({ row, value: efficiency(row)!, percentile: values.length <= 1 ? 50 : Math.round(values.filter(value => value < efficiency(row)!).length / (values.length - 1) * 100) })) }
}
export function efficiencySpeedGroups(rows: readonly Drive[]) {
  return [{ label: '< 30 km/h', min: 0, max: 30 }, { label: '30–80 km/h', min: 30, max: 80 }, { label: '≥ 80 km/h', min: 80, max: Infinity }].flatMap(group => {
    const items = rows.filter(row => row.distanceKm != null && row.distanceKm > 0 && row.durationMinutes != null && row.durationMinutes > 0 &&
      row.distanceKm * 60 / row.durationMinutes >= group.min && row.distanceKm * 60 / row.durationMinutes < group.max)
    const average = aggregateDriveHistory(items).efficiency
    return average.known ? [{ label: group.label, ...average }] : []
  })
}

/** Distribution estimate anchored to the admitted total distance, not an odometer observation. */
export function distanceCurveEstimates(points: readonly { date: string; speedKmh: number | null }[], totalKm: number | null | undefined): (number | null)[] {
  const missing = () => points.map(() => null)
  if (totalKm == null || !Number.isFinite(totalKm) || totalKm < 0 || points.length < 2 ||
    points.some(point => !Number.isFinite(Date.parse(point.date)) || point.speedKmh == null || !Number.isFinite(point.speedKmh) || point.speedKmh < 0)) return missing()
  const cumulative = [0]
  for (let index = 1; index < points.length; index++) {
    const seconds = (Date.parse(points[index].date) - Date.parse(points[index - 1].date)) / 1000
    if (seconds < 0) return missing()
    cumulative.push(cumulative[index - 1] + (points[index].speedKmh! + points[index - 1].speedKmh!) / 2 * seconds / 3600)
  }
  const integrated = cumulative[cumulative.length - 1]
  if (!(integrated > 0)) return totalKm === 0 ? points.map(() => 0) : missing()
  return cumulative.map(value => value / integrated * totalKm)
}

export const CURVE_RENDER_MAX_POINTS = 600
export type CurveRenderPoint = { index: number; time: number; value: number; breakBefore: boolean }
/** Render-only time bucket min/max selection. Receipts and cursor samples are never changed. */
export function selectCurveSamples(source: readonly { time: number; value: number | null }[]) {
  const valid: { index: number; time: number; value: number; segment: number }[] = []
  let segment = 0, open = false
  for (let index = 0; index < source.length; index++) {
    const point = source[index]
    if (point.value == null || !Number.isFinite(point.value) || !Number.isFinite(point.time)) { open = false; continue }
    if (!open) { segment++; open = true }
    valid.push({ index, time: point.time, value: point.value, segment })
  }
  let selected = valid
  const thinned = valid.length > CURVE_RENDER_MAX_POINTS
  if (thinned) {
    const indexes = new Set<number>([0, valid.length - 1])
    let minimum = 0, maximum = 0, zero = -1
    valid.forEach((point, index) => {
      if (point.value < valid[minimum].value) minimum = index
      if (point.value > valid[maximum].value) maximum = index
      if (point.value === 0 && zero < 0) zero = index
    })
    indexes.add(minimum); indexes.add(maximum)
    if (zero >= 0) indexes.add(zero)
    // 297 min/max buckets plus at most five mandatory observations <= 599 points.
    const bucketCount = Math.floor((CURVE_RENDER_MAX_POINTS - 5) / 2)
    const buckets = new Map<number, { min: number; max: number }>()
    const start = valid[0].time, end = valid[valid.length - 1].time
    valid.forEach((point, index) => {
      const fraction = end > start ? (point.time - start) / (end - start) : index / (valid.length - 1)
      const key = Math.min(bucketCount - 1, Math.max(0, Math.floor(fraction * bucketCount)))
      const bucket = buckets.get(key)
      if (!bucket) buckets.set(key, { min: index, max: index })
      else {
        if (point.value < valid[bucket.min].value) bucket.min = index
        if (point.value > valid[bucket.max].value) bucket.max = index
      }
    })
    for (const bucket of buckets.values()) { indexes.add(bucket.min); indexes.add(bucket.max) }
    selected = [...indexes].sort((a, b) => a - b).map(index => valid[index])
  }
  const points: CurveRenderPoint[] = selected.map((point, index) => ({ index: point.index, time: point.time, value: point.value,
    breakBefore: index === 0 || selected[index - 1].segment !== point.segment }))
  return { points, thinned, sourceCount: source.length, validCount: valid.length, segmentCount: segment }
}
