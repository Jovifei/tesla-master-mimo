import type { WebCharge, WebDriveSummary } from './matelink-types'

/**
 * Analysis aggregation, following the miniprogram's reports.ts /
 * history.ts semantics: quarantined rows are excluded everywhere;
 * totals carry their coverage (known of total) instead of silently
 * summing partial data; efficiency pairs only drives that have
 * both energy and distance.
 */

export type Covered = { value: number | null; known: number; total: number }

function coveredTotal<T>(rows: readonly T[], pick: (row: T) => number | null): Covered {
  let sum = 0
  let known = 0
  for (const row of rows) {
    const value = pick(row)
    if (value != null && Number.isFinite(value)) {
      sum += value
      known++
    }
  }
  return { value: known ? sum : null, known, total: rows.length }
}

const notQuarantined = (row: { qualityState: string | null }): boolean => row.qualityState !== 'quarantined'

function inYear<T extends { startDate: string | null }>(rows: readonly T[], year: number): T[] {
  return rows.filter(row => {
    if (!row.startDate) return false
    const time = Date.parse(row.startDate)
    return Number.isFinite(time) && new Date(time).getFullYear() === year
  })
}

export type AnnualSummary = {
  year: number
  tripCount: number
  sessionCount: number
  distance: Covered
  driveEnergy: Covered
  duration: Covered
  efficiencyKwhPer100km: number | null
  chargedEnergy: Covered
  costs: { currency: string; value: number; count: number }[]
}

export function annualSummary(drives: readonly WebDriveSummary[], charges: readonly WebCharge[], year: number): AnnualSummary {
  const trips = inYear(drives, year).filter(notQuarantined)
  const sessions = inYear(charges, year).filter(notQuarantined)
  const distance = coveredTotal(trips, row => (row.distanceKm != null && row.distanceKm >= 0 ? row.distanceKm : null))
  const driveEnergy = coveredTotal(trips, row => row.energyConsumedKwh)
  const duration = coveredTotal(trips, row => (row.durationMinutes != null && row.durationMinutes >= 0 ? row.durationMinutes : null))
  const paired = trips.filter(row => row.energyConsumedKwh != null && (row.distanceKm ?? 0) > 0)
  const pairedEnergy = coveredTotal(paired, row => row.energyConsumedKwh).value
  const pairedDistance = coveredTotal(paired, row => row.distanceKm).value
  const costsMap = new Map<string, { value: number; count: number }>()
  for (const charge of sessions) {
    const currency = charge.currency
    if (!currency || charge.cost == null || !Number.isFinite(charge.cost)) continue
    const old = costsMap.get(currency) ?? { value: 0, count: 0 }
    costsMap.set(currency, { value: old.value + charge.cost, count: old.count + 1 })
  }
  return {
    year,
    tripCount: trips.length,
    sessionCount: sessions.length,
    distance,
    driveEnergy,
    duration,
    efficiencyKwhPer100km:
      pairedEnergy != null && pairedDistance != null && pairedDistance > 0 ? (100 * pairedEnergy) / pairedDistance : null,
    chargedEnergy: coveredTotal(sessions, row => row.energyAddedKwh),
    costs: [...costsMap.entries()].map(([currency, amount]) => ({ currency, ...amount })),
  }
}

export type MonthlyRow = {
  month: string // YYYY-MM
  trips: number
  distanceKm: number | null
  sessions: number
  chargedKwh: number | null
  cost: number | null
  currency: string | null
}

/** The most recent 12 months that appear in the data, newest first. */
export function monthlyRows(drives: readonly WebDriveSummary[], charges: readonly WebCharge[]): MonthlyRow[] {
  const byMonth = new Map<string, { drives: WebDriveSummary[]; charges: WebCharge[] }>()
  const keyOf = (iso: string | null): string | null => {
    if (!iso) return null
    const time = Date.parse(iso)
    if (!Number.isFinite(time)) return null
    const date = new Date(time)
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
  }
  for (const drive of drives.filter(notQuarantined)) {
    const key = keyOf(drive.startDate)
    if (!key) continue
    const bucket = byMonth.get(key) ?? { drives: [], charges: [] }
    bucket.drives.push(drive)
    byMonth.set(key, bucket)
  }
  for (const charge of charges.filter(notQuarantined)) {
    const key = keyOf(charge.startDate)
    if (!key) continue
    const bucket = byMonth.get(key) ?? { drives: [], charges: [] }
    bucket.charges.push(charge)
    byMonth.set(key, bucket)
  }
  return [...byMonth.entries()]
    .sort((a, b) => (a[0] < b[0] ? 1 : -1))
    .slice(0, 12)
    .map(([month, bucket]) => {
      const currencies = new Map<string, number>()
      for (const charge of bucket.charges) {
        if (charge.currency && charge.cost != null) currencies.set(charge.currency, (currencies.get(charge.currency) ?? 0) + charge.cost)
      }
      const [currency, cost] = currencies.size === 1 ? [...currencies.entries()][0] : [null, null]
      return {
        month,
        trips: bucket.drives.length,
        distanceKm: coveredTotal(bucket.drives, row => row.distanceKm).value,
        sessions: bucket.charges.length,
        chargedKwh: coveredTotal(bucket.charges, row => row.energyAddedKwh).value,
        cost,
        currency,
      }
    })
}

export function availableYears(drives: readonly WebDriveSummary[], charges: readonly WebCharge[]): number[] {
  const years = new Set<number>()
  for (const row of [...drives, ...charges]) {
    if (!row.startDate) continue
    const time = Date.parse(row.startDate)
    if (Number.isFinite(time)) years.add(new Date(time).getFullYear())
  }
  return [...years].sort((a, b) => b - a)
}

/* ---- history export (non-location projection, like the miniprogram) ---- */

function csvCell(value: unknown): string {
  if (value == null) return ''
  const numeric = typeof value === 'number' && Number.isFinite(value)
  let text = numeric ? String(value) : String(value).replace(/[\r\n]+/g, ' ')
  if (!numeric && /^[\s]*[=+@-]/.test(text)) text = `'${text}`
  return `"${text.replace(/"/g, '""')}"`
}

const EXPORT_HEADER = ['type', 'start_date', 'end_date', 'distance_km', 'duration_min', 'drive_energy_kwh', 'battery_added_kwh', 'cost', 'currency', 'quality']

function exportRows(drives: readonly WebDriveSummary[], charges: readonly WebCharge[]): unknown[][] {
  const rows: unknown[][] = [EXPORT_HEADER]
  for (const drive of drives.filter(notQuarantined)) {
    rows.push(['drive', drive.startDate, drive.endDate, drive.distanceKm, drive.durationMinutes, drive.energyConsumedKwh, null, null, null, drive.qualityState])
  }
  for (const charge of charges.filter(notQuarantined)) {
    rows.push(['charge', charge.startDate, charge.endDate, null, charge.durationMinutes, null, charge.energyAddedKwh, charge.cost, charge.currency, charge.qualityState])
  }
  return rows
}

export function historyCsv(drives: readonly WebDriveSummary[], charges: readonly WebCharge[]): string {
  return '\uFEFF' + exportRows(drives, charges).map(row => row.map(csvCell).join(',')).join('\r\n')
}

export function historyJson(drives: readonly WebDriveSummary[], charges: readonly WebCharge[], complete = false): string {
  const [header, ...rows] = exportRows(drives, charges)
  const records = rows.map(row => Object.fromEntries(header.map((key, index) => [String(key), row[index] ?? null])))
  return JSON.stringify(
    {
      schema: 1,
      coverage: { complete, basis: 'web_history_pages', includesPrivateLocations: false },
      drives: records.filter(row => row.type === 'drive'),
      charges: records.filter(row => row.type === 'charge'),
    },
    null,
    2
  )
}
