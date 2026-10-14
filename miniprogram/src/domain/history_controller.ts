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
  if (['source', 'startDate', 'endDate', 'startBatteryLevel', 'endBatteryLevel', 'distanceKm'].some(key =>
      (latest[key] ?? null) !== (original[key] ?? null))) return current
  const items = sortHistoryByStartDate(mergeHistoryByKey(current.items, [detail], keyOf).items)
  return { ...current, items, pages: canonicalHistoryPages(current.pages, items, keyOf) }
}
