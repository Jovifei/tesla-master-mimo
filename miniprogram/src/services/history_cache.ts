import type { WechatStorage } from './session'

export type HistoryKind = 'drives' | 'charges'

type HistoryCache<T> = {
  scope: string
  items: T[]
  savedAt: string
}

export function historyCacheKey(kind: HistoryKind, userId: string | null, stableCarId: string): string {
  return `matelink.history.v1.${kind}.${userId || 'anonymous'}.${stableCarId}`
}

export function readHistoryCache<T>(storage: Pick<WechatStorage, 'getStorageSync'>, key: string): T[] {
  try {
    const value = storage.getStorageSync(key) as Partial<HistoryCache<T>> | undefined
    if (!value || !Array.isArray(value.items)) return []
    return value.items
  } catch {
    return []
  }
}

export function writeHistoryCache<T>(storage: Pick<WechatStorage, 'setStorageSync'>, key: string, items: readonly T[]): void {
  const cache: HistoryCache<T> = { scope: key, items: [...items], savedAt: new Date().toISOString() }
  try {
    storage.setStorageSync(key, cache)
  } catch {
    // A full device cache must never turn a successful API response into an error.
  }
}

export function clearHistoryCache(storage: Pick<WechatStorage, 'removeStorageSync'>, key: string): void {
  try {
    storage.removeStorageSync(key)
  } catch {
    // Clearing an optional cache is best effort.
  }
}
