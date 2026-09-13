import type { WechatStorage } from './session'

export type HistoryKind = 'drives' | 'charges'

type HistoryCache<T> = {
  schema: number
  scope: string
  items: T[]
  savedAt: string
}

export const HISTORY_CACHE_SCHEMA = 2

export function historyCacheKey(
  kind: HistoryKind,
  userId: string | null,
  stableCarId: string,
  apiOrigin = 'https://unconfigured.invalid',
  sessionGeneration = 0,
): string {
  return `matelink.history.v${HISTORY_CACHE_SCHEMA}.${encodeURIComponent(apiOrigin)}.${encodeURIComponent(userId || 'anonymous')}.${encodeURIComponent(stableCarId)}.${kind}.${sessionGeneration}`
}

export function readHistoryCache<T>(storage: Pick<WechatStorage, 'getStorageSync'>, key: string): T[] {
  try {
    const value = storage.getStorageSync(key) as Partial<HistoryCache<T>> | undefined
    if (!value || value.schema !== HISTORY_CACHE_SCHEMA || value.scope !== key || !Array.isArray(value.items)) return []
    return value.items
  } catch {
    return []
  }
}

export function writeHistoryCache<T>(storage: Pick<WechatStorage, 'setStorageSync'>, key: string, items: readonly T[]): boolean {
  const cache: HistoryCache<T> = { schema: HISTORY_CACHE_SCHEMA, scope: key, items: [...items], savedAt: new Date().toISOString() }
  try {
    storage.setStorageSync(key, cache)
    return true
  } catch {
    // A full device cache must never turn a successful API response into an error.
    return false
  }
}

export function clearHistoryCache(storage: Pick<WechatStorage, 'removeStorageSync'>, key: string): void {
  try {
    storage.removeStorageSync(key)
  } catch {
    // Clearing an optional cache is best effort.
  }
}
