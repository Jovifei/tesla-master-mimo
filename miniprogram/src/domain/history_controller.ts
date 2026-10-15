import type { HistoryPage } from '../services/types'
import { historyRowKey, isHistoryQuarantined, mergeHistoryByKey, mergeHistoryPages, sortHistoryByStartDate } from './history'

export type HistoryControllerItem = { id: string; sessionId?: string | null; startDate?: string | null }

export type HistoryControllerState<T extends HistoryControllerItem> = {
  pages: HistoryPage<T>[]
  items: T[]
  page: number
}

export type HistoryPageDecision<T extends HistoryControllerItem> = {
  accepted: boolean
  state: HistoryControllerState<T>
  hasMore: boolean
  complete: boolean
  errorReason: string | null
}

export function emptyHistoryControllerState<T extends HistoryControllerItem>(): HistoryControllerState<T> {
  return { pages: [], items: [], page: 0 }
}

/** Accept one server page without advancing the committed page on failure. */
export function acceptHistoryPage<T extends HistoryControllerItem>(
  current: HistoryControllerState<T>,
  result: HistoryPage<T>,
  requestedPage: number,
  keyOf: (item: T) => string = historyRowKey,
): HistoryPageDecision<T> {
  const expectedPage = requestedPage === 1 ? 1 : current.page + 1
  const candidatePages = requestedPage === 1 ? [result] : [...current.pages, result]
  const pageState = mergeHistoryPages([], candidatePages, keyOf)
  if (result.meta.page !== expectedPage || !pageState.validPrefix) {
    return {
      accepted: false,
      state: current,
      hasMore: current.pages.length > 0 ? Boolean(current.pages[current.pages.length - 1].meta.hasMore) : false,
      complete: false,
      errorReason: result.meta.page !== expectedPage ? 'page_number_mismatch' : pageState.errorReason ?? 'pagination_incomplete',
    }
  }
  const merged = mergeHistoryByKey(current.items, result.items, keyOf)
  return {
    accepted: true,
    state: { pages: canonicalHistoryPages(candidatePages, merged.items, keyOf), items: sortHistoryByStartDate(merged.items), page: result.meta.page },
    hasMore: pageState.hasMore,
    complete: pageState.complete,
    errorReason: null,
  }
}

function canonicalHistoryPages<T extends HistoryControllerItem>(pages: HistoryPage<T>[], items: T[], keyOf: (item: T) => string): HistoryPage<T>[] {
  return pages.map(page => ({ ...page, items: page.items.map(row => items.find(item => keyOf(item) === keyOf(row) || item.id === row.id) ?? row) }))
}

/** Details use the current public request ID; stale IDs may never roll a canonical session backwards. */
export function acceptHistoryDetail<T extends HistoryControllerItem>(current: HistoryControllerState<T>, requested: T, detail: T,
  keyOf: (item: T) => string = historyRowKey): HistoryControllerState<T> {
  const row = current.items.find(item => keyOf(item) === keyOf(requested) || item.id === requested.id)
  if (!row || isHistoryQuarantined(row) || isHistoryQuarantined(requested) || row.id !== requested.id || detail.id !== requested.id ||
      (row.sessionId && detail.sessionId && row.sessionId !== detail.sessionId)) return current
  const latest = row as unknown as Record<string, unknown>, original = requested as unknown as Record<string, unknown>
  if (['source', 'qualityState', 'qualityReason', 'startDate', 'endDate', 'startBatteryLevel', 'endBatteryLevel', 'distanceKm',
    'durationMinutes', 'energyConsumedKwh', 'energyAddedKwh', 'energyUsedKwh', 'cost', 'currency', 'currencyCode',
    'speedMaxKmh', 'speedSampleCount', 'speedMetricsSource', 'speedUnit', 'energy'].some(key =>
      JSON.stringify(latest[key] ?? null) !== JSON.stringify(original[key] ?? null))) return current
  const items = sortHistoryByStartDate(mergeHistoryByKey(current.items, [detail], keyOf).items)
  return { ...current, items, pages: canonicalHistoryPages(current.pages, items, keyOf) }
}

export type HistoryInterval<T> = { items: T[]; complete: boolean; receivedPages: number; reason: string | null }

/** Separate statistics snapshot: never advances the user's list cursor or reads orphaned cache rows. */
export async function collectHistoryInterval<T extends HistoryControllerItem>(
  first: HistoryPage<T>, fetchPage: (page: number) => Promise<HistoryPage<T>>, isCurrent: () => boolean,
): Promise<HistoryInterval<T> | null> {
  let state = emptyHistoryControllerState<T>()
  let response = first
  for (let page = 1; page <= 25; page += 1) {
    if (!isCurrent()) return null
    if (response.items.length > 20) return { items: state.items, complete: false, receivedPages: state.page, reason: 'page_size_exceeded' }
    const decision = acceptHistoryPage(state, response, page)
    if (!decision.accepted) return { items: state.items, complete: false, receivedPages: state.page, reason: decision.errorReason }
    state = decision.state
    if (decision.complete) return { items: state.items, complete: true, receivedPages: page, reason: null }
    if (page === 25 || state.items.length >= 500) return { items: state.items, complete: false, receivedPages: page, reason: 'limit' }
    try { response = await fetchPage(page + 1) }
    catch { return isCurrent() ? { items: state.items, complete: false, receivedPages: state.page, reason: 'request_failed' } : null }
  }
  return null
}

/** Enrich only rows actually in this snapshot; cached records cannot expand its coverage. */
export function enrichHistoryInterval<T extends HistoryControllerItem>(rows: readonly T[], canonical: readonly T[]): T[] {
  return rows.map(row => {
    const old = canonical.find(item => historyRowKey(item) === historyRowKey(row) || item.id === row.id)
    return old ? mergeHistoryByKey([old], [row], historyRowKey).items[0] : row
  })
}
