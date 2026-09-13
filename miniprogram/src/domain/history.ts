import type { HistoryPage } from '../services/types'

export type HistoryLoadState = 'loading' | 'partial' | 'ready' | 'cached' | 'failed'
export type HistoryMergeResult<T> = { items: T[]; retainedLocalCount: number; addedRemoteCount: number }
export type HistoryPagesMergeResult<T> = HistoryMergeResult<T> & { receivedPages: number; complete: boolean }

type HistoryIdentity = { id: string; sessionId?: string | null; startDate?: string | null }
type Row = Record<string, unknown>
const unverifiedSources = new Set(['local_import', 'snapshot_estimate', 'physical_model', 'inferred_soc_jump'])

export function historyScopeKey(userId: string | null, carId: number): string {
  return `${userId || 'anonymous'}:${carId}`
}

// Only call within an account + stable vehicle + kind scope. Mutable dates are
// not identities. Do not guess that equal times on two vehicles mean one trip.
export function historyRowKey(item: HistoryIdentity): string {
  const sessionId = item.sessionId?.trim()
  return sessionId ? `session:${sessionId}` : `id:${String(item.id).trim()}`
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
function conservativeEvidence<T>(item: T): T {
  const row = asRow(item)
  if (!row || quarantined(row)) return item
  if ((row.qualityState === 'observed' || row.qualityState === 'derived') && !trusted(row)) {
    return { ...row, qualityState: 'incomplete', qualityReason: 'unverified_source' } as T
  }
  return item
}

// Preserve a stronger record whole, rather than grafting unverified numbers onto
// observed metadata. In the absence of a server revision, same-source, same-grade
// incoming observations win non-null fields; null is absence, not a deletion.
// Explicit corrections/deletions need server revisions/tombstones in M3-B.
export function mergeHistoryEvidence<T>(local: T, incoming: T): T {
  const old = asRow(local), next = asRow(incoming)
  if (!old || !next) return incoming
  let merged: Row
  if (quarantined(next)) merged = { ...next }
  else if (quarantined(old)) merged = { ...old } // only an explicit server revision may release quarantine
  else if (trusted(next) && !trusted(old)) merged = { ...next }
  else if (trusted(old) && !trusted(next)) merged = { ...old }
  else if (trusted(next) && trusted(old)) {
    const oldRank = qualityRank(old), nextRank = qualityRank(next)
    if (nextRank < oldRank) {
      merged = { ...old }
    } else if (nextRank > oldRank || text(next.source) !== text(old.source) || next.qualityState !== old.qualityState) {
      // Sources/grades cannot be relabelled by filling from a different record.
      merged = { ...next }
    } else {
      merged = { ...old }
      for (const [key, value] of Object.entries(next)) if (!missing(value)) merged[key] = value
    }
  } else {
    // Legacy records without trustworthy provenance stay legacy. Keep their
    // existing fields; this path must not launder an unverified quality flag.
    merged = { ...old }
    for (const [key, value] of Object.entries(next)) if (missing(merged[key])) merged[key] = value
    if (old.qualityState === 'incomplete' || next.qualityState === 'incomplete' ||
        merged.qualityState === 'observed' || merged.qualityState === 'derived') {
      merged.qualityState = 'incomplete'
    }
  }
  // A server-side public ID can change for the same canonical session. Details
  // must use the current incoming ID, even when cached evidence is stronger.
  if (!missing(next.id)) merged.id = next.id
  if (!missing(next.sessionId)) merged.sessionId = next.sessionId
  return conservativeEvidence(merged as T)
}

function compatibleID(a: unknown, b: unknown): boolean {
  const left = asRow(a), right = asRow(b)
  if (!left || !right || missing(left.id) || missing(right.id) || String(left.id) !== String(right.id)) return false
  const ls = text(left.sessionId), rs = text(right.sessionId)
  return !ls || !rs || ls === rs
}

export function mergeHistoryByKey<T>(localItems: readonly T[], remoteItems: readonly T[], keyOf: (item: T) => string): HistoryMergeResult<T> {
  const merged: T[] = []
  const localKeys = new Set<string>()
  let addedRemoteCount = 0
  // Canonicalize existing local duplicates too. No approximate time-window match.
  const add = (item: T, remote: boolean) => {
    const key = keyOf(item)
    if (!key) return
    let index = merged.findIndex(row => keyOf(row) === key)
    if (index < 0) index = merged.findIndex(row => compatibleID(row, item))
    if (index < 0) {
      merged.push(conservativeEvidence(item))
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
  let contiguous = true
  pages.forEach((page, index) => {
    if (page.meta.page !== index + 1 || (index < pages.length - 1 && !page.meta.hasMore)) contiguous = false
    const before = seen.size
    page.items.forEach(item => seen.add(keyOf(item)))
    if ((page.items.length > 0 && seen.size === before) || (page.items.length === 0 && page.meta.hasMore)) contiguous = false
  })
  return { ...merged, receivedPages: pages.length, complete: Boolean(contiguous && pages.length && !pages[pages.length - 1].meta.hasMore) }
}

export function pageCanContinue<T>(page: HistoryPage<T> | null): boolean { return Boolean(page?.meta.hasMore) }
