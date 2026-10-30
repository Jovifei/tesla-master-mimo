import type { Charge, Drive, HistoryPage } from '../services/types'
import { mergeChargeEnergyRecord, mergeDriveEnergyRecord, sameEnergyInstant } from './drive_energy'

export type HistoryLoadState = 'loading' | 'partial' | 'ready' | 'cached' | 'failed'
export type HistoryMergeResult<T> = { items: T[]; retainedLocalCount: number; addedRemoteCount: number }
export type HistoryPagesMergeResult<T> = HistoryMergeResult<T> & {
  receivedPages: number
  validPrefix: boolean
  complete: boolean
  hasMore: boolean
  errorReason: string | null
}

type HistoryIdentity = {
  id: string
  sessionId?: string | null
  startDate?: string | null
  source?: string | null
  sourceInstanceId?: string | null
  sourceVehicleId?: string | null
  sourceRecordId?: string | null
}
type Row = Record<string, unknown>
const unverifiedSources = new Set(['local_import', 'snapshot_estimate', 'physical_model', 'inferred_soc_jump'])

export function historyScopeKey(userId: string | null, carId: number): string {
  return `${userId || 'anonymous'}:${carId}`
}

// Only call within an account + stable vehicle + kind scope. Mutable dates are
// not identities. Do not guess that equal times on two vehicles mean one trip.
export function historyRowKey(item: HistoryIdentity): string {
  const session = text(item.sessionId)
  return JSON.stringify([text(item.source), text(item.sourceInstanceId), text(item.sourceVehicleId), text(item.sourceRecordId),
    session ? 'session' : 'id', session || text(item.id)])
}

/** Compare only within the same account, API origin, stable vehicle and history kind. */
export function sameHistoryIdentity(a: unknown, b: unknown): boolean {
  const left = asRow(a), right = asRow(b)
  if (!left || !right) return false
  for (const field of ['source', 'sourceInstanceId', 'sourceVehicleId', 'sourceRecordId']) {
    if ((left[field] != null && typeof left[field] !== 'string') || (right[field] != null && typeof right[field] !== 'string')) return false
    if (text(left[field]) !== text(right[field])) return false
  }
  const ls = text(left.sessionId), rs = text(right.sessionId)
  if (ls || rs) return Boolean(ls && rs && ls === rs)
  const id = text(left.id)
  return Boolean(id && id === text(right.id))
}

function timestamp(value: string | null | undefined): number {
  const parsed = value ? Date.parse(value) : Number.NaN
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY
}

export function sortHistoryByStartDate<T extends { startDate?: string | null }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => {
    const left = timestamp(a.startDate), right = timestamp(b.startDate)
    return left === right ? 0 : left < right ? 1 : -1
  })
}

function asRow(value: unknown): Row | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Row : null
}
function missing(value: unknown): boolean { return value == null || value === '' }
function text(value: unknown): string { return typeof value === 'string' ? value.trim() : '' }
function trusted(row: Row): boolean {
  const quality = text(row.qualityState), source = text(row.source)
  return (quality === 'observed' || quality === 'derived') && source !== '' && !unverifiedSources.has(source)
}
function qualityRank(row: Row): number {
  switch (text(row.qualityState)) {
    case 'observed': return 2
    case 'derived': return 1
    default: return 0
  }
}
function quarantined(row: Row): boolean { return text(row.qualityState).toLowerCase() === 'quarantined' }
export function isHistoryIdentityAmbiguous(item: unknown): boolean {
  return asRow(item)?.identityAmbiguous === true
}
export function isHistoryQuarantined(item: unknown): boolean {
  const row = asRow(item)
  return Boolean(row && (quarantined(row) || isHistoryIdentityAmbiguous(row)))
}
function conservativeEvidence<T>(item: T): T {
  const row = asRow(item)
  if (!row || quarantined(row)) return item
  if ((row.qualityState === 'observed' || row.qualityState === 'derived') && !trusted(row)) {
    return { ...row, qualityState: 'incomplete', qualityReason: 'unverified_source' } as T
  }
  return item
}

/** A missing or invalid endpoint is not evidence of a corrected window. */
function changedWindow(a: Row, b: Row): boolean {
  return ['startDate', 'endDate'].some(key =>
    sameEnergyInstant(a[key], a[key]) && sameEnergyInstant(b[key], b[key]) && !sameEnergyInstant(a[key], b[key]))
}

function missingKnownEndpoint(previous: Row, incoming: Row): boolean {
  return ['startDate', 'endDate'].some(key => sameEnergyInstant(previous[key], previous[key]) && !sameEnergyInstant(incoming[key], incoming[key]))
}

// Preserve a stronger record whole, rather than grafting unverified numbers onto
// observed metadata. In the absence of a server revision, same-source, same-grade
// incoming observations win non-null fields; null is absence, not a deletion.
// Explicit corrections/deletions need server revisions/tombstones in M3-B.
export function mergeHistoryEvidence<T>(local: T, incoming: T): T {
  const old = asRow(local), next = asRow(incoming)
  if (!old || !next) return incoming
  let merged: Row
  let mergeFields = false
  if (quarantined(next)) merged = { ...next }
  else if (quarantined(old)) merged = { ...old } // only an explicit server revision may release quarantine
  else if (trusted(next) && !trusted(old)) merged = { ...next }
  else if (trusted(old) && !trusted(next)) merged = { ...old }
  else if (trusted(next) && trusted(old)) {
    const oldRank = qualityRank(old), nextRank = qualityRank(next)
    if (nextRank < oldRank) {
      merged = { ...old }
    } else if (nextRank > oldRank || text(next.source) !== text(old.source) || next.qualityState !== old.qualityState || changedWindow(old, next)) {
      // Sources/grades cannot be relabelled by filling from a different record.
      merged = { ...next }
    } else if (missingKnownEndpoint(old, next)) {
      // A sparse response cannot invalidate a previously proven window.
      merged = { ...old }
    } else {
      mergeFields = true
      merged = { ...old }
      for (const [key, value] of Object.entries(next)) if (!missing(value)) merged[key] = value
    }
  } else {
    // Legacy records without trustworthy provenance stay legacy. Keep their
    // existing fields; this path must not launder an unverified quality flag.
    mergeFields = !changedWindow(old, next) && !missingKnownEndpoint(old, next)
    merged = { ...old }
    if (mergeFields) for (const [key, value] of Object.entries(next)) if (missing(merged[key])) merged[key] = value
    if (old.qualityState === 'incomplete' || next.qualityState === 'incomplete' ||
        merged.qualityState === 'observed' || merged.qualityState === 'derived') {
      merged.qualityState = 'incomplete'
    }
  }
  // A server-side public ID can change for the same canonical session. Details
  // must use the current incoming ID, even when cached evidence is stronger.
  // A weaker response for a contradictory window cannot rename retained evidence.
  const oldWindowRetained = changedWindow(old, next) && merged.startDate === old.startDate && merged.endDate === old.endDate
  const unresolvedSource = (old.identityAmbiguous === true && old.identityAmbiguousReason !== 'conflicting_window') ||
    (next.identityAmbiguous === true && next.identityAmbiguousReason !== 'conflicting_window')
  const verifiedWindow = trusted(next) && qualityRank(next) >= qualityRank(old) &&
    sameEnergyInstant(next.startDate, next.startDate) && sameEnergyInstant(next.endDate, next.endDate)
  if (unresolvedSource || oldWindowRetained || (old.identityAmbiguous === true && !verifiedWindow) || next.identityAmbiguous === true) {
    merged.identityAmbiguous = true
    merged.identityAmbiguousReason = unresolvedSource ? 'source_identity_incomplete' : 'conflicting_window'
  } else {
    delete merged.identityAmbiguous
    delete merged.identityAmbiguousReason
  }
  if (!missing(next.id) && !oldWindowRetained) merged.id = next.id
  if (!missing(next.sessionId)) merged.sessionId = next.sessionId
  const admitted = conservativeEvidence(merged as T)
  // Whole-record admission also owns its proof. Rejected or displaced records
  // cannot contribute energy beneath the chosen record's source/quality.
  if (!mergeFields) return admitted
  if ('energyConsumedKwh' in old || 'energyReceipt' in old || 'energyConsumedKwh' in next || 'energyReceipt' in next) {
    return mergeDriveEnergyRecord(local as unknown as Drive, incoming as unknown as Drive, admitted as unknown as Drive) as unknown as T
  }
  if ('energyAddedKwh' in old || 'energyAddedKwh' in next) {
    return mergeChargeEnergyRecord(local as unknown as Charge, incoming as unknown as Charge, admitted as unknown as Charge) as unknown as T
  }
  return admitted
}

export function mergeHistoryByKey<T>(localItems: readonly T[], remoteItems: readonly T[], keyOf: (item: T) => string): HistoryMergeResult<T> {
  const merged: T[] = []
  const localKeys = new Set<string>()
  let addedRemoteCount = 0
  // Canonicalize existing local duplicates too. No approximate time-window match.
  const add = (item: T, remote: boolean) => {
    let incoming = item
    const key = keyOf(incoming)
    if (!key) return
    const index = merged.findIndex(row => sameHistoryIdentity(row, incoming) &&
      (keyOf(row) === key || text(asRow(row)?.id) === text(asRow(incoming)?.id)))
    if (index < 0) {
      // Missing source fields cannot prove two equal public IDs/sessions are
      // the same record. Retain both, but isolate the less specific evidence.
      for (let i = 0; i < merged.length; i++) {
        const old = asRow(merged[i]), next = asRow(incoming)
        if (!old || !next) continue
        const oldSession = text(old.sessionId), nextSession = text(next.sessionId)
        if (oldSession && nextSession ? oldSession !== nextSession : !text(old.id) || text(old.id) !== text(next.id)) continue
        let oldMissing = false, nextMissing = false, conflict = false
        for (const field of ['source', 'sourceInstanceId', 'sourceVehicleId', 'sourceRecordId']) {
          const a = text(old[field]), b = text(next[field])
          if (a && b && a !== b) { conflict = true; break }
          if (!a && b) oldMissing = true
          if (a && !b) nextMissing = true
        }
        if (conflict || (!oldMissing && !nextMissing)) continue
        if (oldMissing) merged[i] = { ...old, identityAmbiguous: true, identityAmbiguousReason: 'source_identity_incomplete' } as T
        if (nextMissing) incoming = { ...next, identityAmbiguous: true, identityAmbiguousReason: 'source_identity_incomplete' } as T
      }
      merged.push(conservativeEvidence(incoming))
      if (remote) addedRemoteCount += 1
    } else merged[index] = mergeHistoryEvidence(merged[index], item)
  }
  for (const item of localItems) { localKeys.add(keyOf(item)); add(item, false) }
  for (const item of remoteItems) add(item, true)
  return { items: merged, retainedLocalCount: localKeys.size, addedRemoteCount }
}

export function mergeHistoryPages<T>(localItems: readonly T[], pages: readonly HistoryPage<T>[], keyOf: (item: T) => string): HistoryPagesMergeResult<T> {
  const merged = mergeHistoryByKey(localItems, pages.flatMap(page => page.items), keyOf)
  const seen = new Set<string>()
  let validPrefix = true
  let errorReason: string | null = null
  pages.forEach((page, index) => {
    if (!validPrefix) return
    if (page.meta.page !== index + 1) {
      validPrefix = false
      errorReason = 'page_number_mismatch'
      return
    }
    if (index < pages.length - 1 && !pages[index].meta.hasMore) {
      validPrefix = false
      errorReason = 'has_more_contradiction'
      return
    }
    const before = seen.size
    page.items.forEach(item => seen.add(keyOf(item)))
    if (page.items.length > 0 && seen.size === before) {
      validPrefix = false
      errorReason = 'repeated_page'
      return
    }
    if (page.items.length === 0 && page.meta.hasMore && !(page.meta.discardedRows && page.meta.discardedRows > 0)) {
      validPrefix = false
      errorReason = 'empty_page_with_more'
      return
    }
    if (page.meta.totalPages != null && page.meta.page < page.meta.totalPages && !page.meta.hasMore) {
      validPrefix = false
      errorReason = 'total_pages_contradiction'
    }
  })
  const lastPage = pages[pages.length - 1]
  const hasMore = Boolean(validPrefix && lastPage?.meta.hasMore)
  return {
    ...merged,
    receivedPages: pages.length,
    validPrefix,
    complete: Boolean(validPrefix && lastPage && !lastPage.meta.hasMore),
    hasMore,
    errorReason,
  }
}

export function pageCanContinue<T>(page: HistoryPage<T> | null): boolean { return Boolean(page?.meta.hasMore) }

export type CoveredMetric = { value: number | null; known: number; total: number }
const finite = (value: number | null | undefined): number | null => value != null && Number.isFinite(value) ? value : null
export function coveredTotal<T>(rows: readonly T[], valueOf: (row: T) => number | null): CoveredMetric {
  let value: number | null = null, known = 0
  for (const row of rows) {
    const number = valueOf(row)
    if (number == null || !Number.isFinite(number)) continue
    value = (value ?? 0) + number
    known += 1
  }
  return { value: finite(value), known, total: rows.length }
}

/** Independent totals and paired efficiency have deliberately different denominators. */
export function aggregateDriveHistory(items: readonly Drive[]) {
  const rows = items.filter(item => !isHistoryQuarantined(item))
  const distanceOf = (row: Drive) => row.distanceKm != null && row.distanceKm >= 0 ? finite(row.distanceKm) : null
  const energyOf = (row: Drive) => row.energy && row.energy.quality !== 'unknown' ? finite(row.energy.energyKwh) : null
  const distance = coveredTotal(rows, distanceOf)
  const energy = coveredTotal(rows, energyOf)
  const duration = coveredTotal(rows, row => row.durationMinutes != null && row.durationMinutes >= 0 ? finite(row.durationMinutes) : null)
  const paired = rows.filter(row => energyOf(row) != null && (distanceOf(row) ?? 0) > 0)
  const pairedEnergy = coveredTotal(paired, energyOf).value
  const pairedDistance = coveredTotal(paired, distanceOf).value
  const estimated = paired.filter(row => row.energy?.quality === 'estimated').length
  return { distance, energy, duration, efficiency: { value: pairedEnergy != null && pairedDistance != null && pairedDistance > 0
    ? finite(100 * pairedEnergy / pairedDistance) : null, known: paired.length, total: rows.length, estimated },
    estimatedEnergy: rows.filter(row => energyOf(row) != null && row.energy?.quality === 'estimated').length }
}
