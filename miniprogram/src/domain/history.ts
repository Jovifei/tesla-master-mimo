import type { HistoryPage } from '../services/types'

export type HistoryLoadState = 'loading' | 'partial' | 'ready' | 'cached' | 'failed'

export type HistoryMergeResult<T> = {
  items: T[]
  retainedLocalCount: number
  addedRemoteCount: number
}

export type HistoryPagesMergeResult<T> = HistoryMergeResult<T> & {
  receivedPages: number
  complete: boolean
}

type HistoryIdentity = {
  id: string
  sessionId?: string | null
  startDate?: string | null
}

export function historyScopeKey(userId: string | null, carId: number): string {
  return `${userId || 'anonymous'}:${carId}`
}

export function historyRowKey(item: HistoryIdentity): string {
  const sessionId = item.sessionId?.trim()
  return sessionId || `${item.startDate || 'unknown'}:${item.id}`
}

export function sortHistoryByStartDate<T extends { startDate?: string | null }>(items: readonly T[]): T[] {
  return [...items].sort((left, right) => {
    const leftTime = left.startDate ? Date.parse(left.startDate) : Number.NEGATIVE_INFINITY
    const rightTime = right.startDate ? Date.parse(right.startDate) : Number.NEGATIVE_INFINITY
    if (Number.isNaN(leftTime) || Number.isNaN(rightTime)) return 0
    return rightTime - leftTime
  })
}

function fillMissingFields<T>(local: T, remote: T): T {
  if (!local || typeof local !== 'object' || Array.isArray(local) || !remote || typeof remote !== 'object' || Array.isArray(remote)) {
    return local
  }
  const merged = { ...(local as Record<string, unknown>) }
  for (const [key, value] of Object.entries(remote as Record<string, unknown>)) {
    if (merged[key] == null || merged[key] === '') merged[key] = value
  }
  return merged as T
}

export function mergeHistoryByKey<T>(
  localItems: readonly T[],
  remoteItems: readonly T[],
  keyOf: (item: T) => string,
): HistoryMergeResult<T> {
  const merged = [...localItems]
  const seen = new Set(localItems.map(keyOf))
  let addedRemoteCount = 0

  for (const item of remoteItems) {
    const key = keyOf(item)
    if (!key) continue
    if (seen.has(key)) {
      const index = merged.findIndex(existing => keyOf(existing) === key)
      if (index >= 0) merged[index] = fillMissingFields(merged[index], item)
      continue
    }
    seen.add(key)
    merged.push(item)
    addedRemoteCount += 1
  }

  return {
    items: merged,
    retainedLocalCount: localItems.length,
    addedRemoteCount,
  }
}

export function mergeHistoryPages<T>(
  localItems: readonly T[],
  pages: readonly HistoryPage<T>[],
  keyOf: (item: T) => string,
): HistoryPagesMergeResult<T> {
  const remoteItems = pages.flatMap(page => page.items)
  const merged = mergeHistoryByKey(localItems, remoteItems, keyOf)
  const lastPage = pages[pages.length - 1]
  return {
    ...merged,
    receivedPages: pages.length,
    complete: Boolean(lastPage && !lastPage.meta.hasMore),
  }
}

export function pageCanContinue<T>(page: HistoryPage<T> | null): boolean {
  return Boolean(page?.meta.hasMore)
}
