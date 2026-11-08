import type { WebCharge, WebDriveSummary } from './matelink-types'
import { aggregateEfficiency } from './drive-energy.ts'

/**
 * Derivations for the TeslaMate-style panel composition (v4 rebuild).
 * Pure presentation math over the existing normalized records: no new
 * endpoints, no invented series. Quarantined rows are excluded from
 * aggregates, matching the analysis module's rule.
 */

const notQuarantined = (row: { qualityState: string | null }): boolean => row.qualityState !== 'quarantined'

/* ------------------------- daily activity ------------------------- */

export type DayActivity = {
  key: string
  label: string
  fullLabel: string
  distanceKm: number
  chargedKwh: number
}

function dayKey(date: Date): string {
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
}

/** Per-day driven distance and charged energy over the last `days` local days (oldest first). */
export function dailyActivity(
  drives: readonly WebDriveSummary[],
  charges: readonly WebCharge[],
  days = 14,
  now = new Date()
): DayActivity[] {
  const buckets: DayActivity[] = []
  const byKey = new Map<string, DayActivity>()
  for (let offset = days - 1; offset >= 0; offset--) {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - offset)
    const entry: DayActivity = {
      key: dayKey(date),
      label: `${date.getMonth() + 1}/${date.getDate()}`,
      fullLabel: `${date.getMonth() + 1}月${date.getDate()}日`,
      distanceKm: 0,
      chargedKwh: 0,
    }
    buckets.push(entry)
    byKey.set(entry.key, entry)
  }
  for (const drive of drives) {
    if (!notQuarantined(drive) || !drive.startDate || drive.distanceKm == null) continue
    const at = new Date(drive.startDate)
    if (Number.isNaN(at.getTime())) continue
    const bucket = byKey.get(dayKey(at))
    if (bucket) bucket.distanceKm += drive.distanceKm
  }
  for (const charge of charges) {
    if (!notQuarantined(charge) || !charge.startDate || charge.energyAddedKwh == null) continue
    const at = new Date(charge.startDate)
    if (Number.isNaN(at.getTime())) continue
    const bucket = byKey.get(dayKey(at))
    if (bucket) bucket.chargedKwh += charge.energyAddedKwh
  }
  return buckets
}

/* ------------------------- state timeline ------------------------- */

export type StateSegmentKind = 'drive' | 'charge' | 'parked'
export type StateSegment = { start: number; end: number; kind: StateSegmentKind }

const KIND_PRIORITY: Record<StateSegmentKind, number> = { parked: 0, charge: 1, drive: 2 }

/**
 * Full-coverage state segments over [windowStart, windowEnd] built from
 * real drive/charge intervals; every gap between them is parked time.
 * Overlaps resolve drive > charge > parked, matching what the car was
 * actually doing (a drive cannot be a charge at the same instant).
 */
export function stateSegments(
  drives: readonly WebDriveSummary[],
  charges: readonly WebCharge[],
  windowStart: number,
  windowEnd: number
): StateSegment[] {
  if (!(windowEnd > windowStart)) return []
  const events: { start: number; end: number; kind: StateSegmentKind }[] = []
  const push = (startIso: string | null, endIso: string | null, kind: StateSegmentKind) => {
    if (!startIso || !endIso) return
    const start = Math.max(windowStart, Date.parse(startIso))
    const end = Math.min(windowEnd, Date.parse(endIso))
    if (Number.isFinite(start) && Number.isFinite(end) && end > start) events.push({ start, end, kind })
  }
  for (const drive of drives) if (notQuarantined(drive)) push(drive.startDate, drive.endDate, 'drive')
  for (const charge of charges) if (notQuarantined(charge)) push(charge.startDate, charge.endDate, 'charge')
  const bounds = new Set<number>([windowStart, windowEnd])
  for (const event of events) {
    bounds.add(event.start)
    bounds.add(event.end)
  }
  const sorted = [...bounds].sort((a, b) => a - b)
  const segments: StateSegment[] = []
  for (let i = 0; i + 1 < sorted.length; i++) {
    const start = sorted[i]
    const end = sorted[i + 1]
    if (end <= start) continue
    const mid = (start + end) / 2
    let kind: StateSegmentKind = 'parked'
    for (const event of events) {
      if (mid >= event.start && mid < event.end && KIND_PRIORITY[event.kind] > KIND_PRIORITY[kind]) kind = event.kind
    }
    const last = segments[segments.length - 1]
    if (last && last.kind === kind && last.end === start) last.end = end
    else segments.push({ start, end, kind })
  }
  return segments
}

/* --------------------------- charge math --------------------------- */

/** 元/kWh, derived from the record's own cost and added energy; null when either side is missing. */
export function unitPricePerKwh(charge: WebCharge): number | null {
  if (charge.cost == null || charge.energyAddedKwh == null || charge.energyAddedKwh <= 0) return null
  return charge.cost / charge.energyAddedKwh
}

/** Mean charging power over the session: added energy / duration. */
export function averagePowerKw(charge: WebCharge): number | null {
  if (charge.energyAddedKwh == null || charge.durationMinutes == null || charge.durationMinutes <= 0) return null
  return charge.energyAddedKwh / (charge.durationMinutes / 60)
}

/* --------------------------- drive math ---------------------------- */

export type PairedEfficiency = { kwhPer100km: number | null; pairedCount: number; totalCount: number }

/**
 * Fleet efficiency under the local App caliber (drive-energy.ts):
 * each drive's energy is resolved client-side first (qualified
 * contract / legacy scalar / SOC-difference estimate), then
 * Σenergy / Σdistance over the drives that resolved.
 * pairedCount/totalCount expose the coverage honestly.
 */
export function pairedEfficiency(drives: readonly WebDriveSummary[], model?: string | null): PairedEfficiency {
  const aggregate = aggregateEfficiency(drives, model)
  return { kwhPer100km: aggregate.kwhPer100km, pairedCount: aggregate.resolvedCount, totalCount: aggregate.totalCount }
}
