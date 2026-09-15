import type { HistoryPage } from '../services/types'
import { historyRowKey, mergeHistoryByKey, mergeHistoryPages, sortHistoryByStartDate } from './history'

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
    state: { pages: candidatePages, items: sortHistoryByStartDate(merged.items), page: result.meta.page },
    hasMore: pageState.hasMore,
    complete: pageState.complete,
    errorReason: null,
  }
}
