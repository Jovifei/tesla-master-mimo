export type HistoryLoadState = 'loading' | 'partial' | 'ready' | 'cached' | 'failed'

export type HistoryPage<T> = {
  items: T[]
  page: number
  total: number | null
  totalPages: number | null
}

export type HistoryMergeResult<T> = {
  items: T[]
  retainedLocalCount: number
  addedRemoteCount: number
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
    if (seen.has(key)) continue
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
