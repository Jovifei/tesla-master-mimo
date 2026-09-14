import type { WechatStorage } from './session'
import { historyRowKey, mergeHistoryByKey } from '../domain/history'

export type HistoryKind = 'drives' | 'charges'

type HistoryCache<T> = {
  schema: number
  scope: string
  items: T[]
  savedAt: string
}

export const HISTORY_CACHE_SCHEMA = 3
const PREVIOUS_HISTORY_CACHE_SCHEMA = 2

export function historyCacheKey(
  kind: HistoryKind,
  userId: string | null,
  stableCarId: string,
  apiOrigin = 'https://unconfigured.invalid',
  _sessionGeneration?: number,
): string {
  return `matelink.history.v${HISTORY_CACHE_SCHEMA}.${encodeURIComponent(apiOrigin)}.${encodeURIComponent(userId || 'anonymous')}.${encodeURIComponent(stableCarId)}.${kind}`
}

export function readHistoryCache<T>(storage: Pick<WechatStorage, 'getStorageSync' | 'setStorageSync'>, key: string): T[] {
  try {
    const value = storage.getStorageSync(key) as Partial<HistoryCache<T>> | undefined
    if (value && value.schema === HISTORY_CACHE_SCHEMA && value.scope === key && Array.isArray(value.items)) return value.items
    const storageInfo = storage as Pick<WechatStorage, 'getStorageInfoSync'>
    if (typeof storageInfo.getStorageInfoSync !== 'function') return []
    const legacyPrefix = key.replace(`.v${HISTORY_CACHE_SCHEMA}.`, `.v${PREVIOUS_HISTORY_CACHE_SCHEMA}.`) + '.'
    const legacyItems: T[] = []
    for (const legacyKey of storageInfo.getStorageInfoSync().keys ?? []) {
      if (!legacyKey.startsWith(legacyPrefix)) continue
      const legacy = storage.getStorageSync(legacyKey) as Partial<HistoryCache<T>> | undefined
      if (!legacy || legacy.schema !== PREVIOUS_HISTORY_CACHE_SCHEMA || legacy.scope !== legacyKey || !Array.isArray(legacy.items)) continue
      legacyItems.push(...legacy.items)
    }
    if (legacyItems.length === 0) return []
    const merged = mergeHistoryByKey([], legacyItems, item => {
      const row = item as T & { id?: string; sessionId?: string | null; startDate?: string | null }
      return row.id ? historyRowKey({ id: row.id, sessionId: row.sessionId, startDate: row.startDate }) : JSON.stringify(row)
    }).items
    try { storage.setStorageSync(key, { schema: HISTORY_CACHE_SCHEMA, scope: key, items: merged, savedAt: new Date().toISOString() }) } catch { /* online data remains usable */ }
    return merged
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
