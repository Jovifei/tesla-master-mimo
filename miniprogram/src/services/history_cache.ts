import type { WechatStorage } from './session'
import { historyRowKey, mergeHistoryByKey } from '../domain/history'
import { canStoreCache } from './cache_budget'

export type HistoryKind = 'drives' | 'charges'

type HistoryCache<T> = {
  schema: number
  scope: string
  items: T[]
  savedAt: string
}

export const HISTORY_CACHE_MAX_ROWS = 500
export const HISTORY_CACHE_MAX_BYTES = 800 * 1024
export const HISTORY_CACHE_SCHEMA = 3

export type HistoryCacheEvidence = { rows: number; qualified: number; quarantined: number; savedAt: string | null }

/** Diagnostic-only inspection. Never enumerates, migrates, writes or returns private history rows. */
export function inspectHistoryCache(storage: Pick<WechatStorage, 'getStorageSync'>, userId: string, stableId: string, origin: string): HistoryCacheEvidence {
  const evidence: HistoryCacheEvidence = { rows: 0, qualified: 0, quarantined: 0, savedAt: null }
  if (!userId.trim() || !stableId.trim() || !origin.startsWith('https://')) return evidence
  for (const kind of ['drives', 'charges'] as const) {
    const key = historyCacheKey(kind, userId, stableId, origin)
    try {
      const value = storage.getStorageSync(key) as Partial<HistoryCache<unknown>> | null
      if (value?.schema !== HISTORY_CACHE_SCHEMA || value.scope !== key || !Array.isArray(value.items)) continue
      const rows = value.items.slice(0, HISTORY_CACHE_MAX_ROWS).filter((item): item is Record<string, unknown> =>
        Boolean(item && typeof item === 'object' && typeof (item as Record<string, unknown>).id === 'string' && String((item as Record<string, unknown>).id).trim()))
      evidence.rows += rows.length
      evidence.qualified += rows.filter(row => row.qualityState === 'observed' || row.qualityState === 'derived').length
      evidence.quarantined += rows.filter(row => row.qualityState === 'quarantined').length
      if (typeof value.savedAt === 'string' && Number.isFinite(Date.parse(value.savedAt)) &&
          (!evidence.savedAt || Date.parse(value.savedAt) > Date.parse(evidence.savedAt))) evidence.savedAt = value.savedAt
    } catch { /* An unreadable archive supplies no evidence. */ }
  }
  return evidence
}
const PREVIOUS_HISTORY_CACHE_SCHEMA = 2
const encodeScope = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')

export function historyCacheKey(
  kind: HistoryKind,
  userId: string | null,
  stableCarId: string,
  apiOrigin = 'https://unconfigured.invalid',
  _sessionGeneration?: number,
): string {
  return `matelink.history.v${HISTORY_CACHE_SCHEMA}.${encodeScope(apiOrigin)}.${encodeScope(userId || 'anonymous')}.${encodeScope(stableCarId)}.${kind}`
}

/** WeChat quotas count UTF-8 bytes, not JavaScript UTF-16 code units. */
export function historyUtf8Bytes(value: string): number {
  let bytes = 0
  for (let i = 0; i < value.length; i += 1) {
    const unit = value.charCodeAt(i)
    if (unit < 0x80) bytes++
    else if (unit < 0x800) bytes += 2
    else if (unit >= 0xd800 && unit <= 0xdbff && i + 1 < value.length && value.charCodeAt(i + 1) >= 0xdc00 && value.charCodeAt(i + 1) <= 0xdfff) { bytes += 4; i++ }
    else bytes += 3
  }
  return bytes
}
function cacheProjection(value: unknown, depth = 0): unknown {
  if (depth > 10) return null
  if (Array.isArray(value)) return value.slice(0, 100).map(item => cacheProjection(item, depth + 1))
  if (!value || typeof value !== 'object') return value
  const result: Record<string, unknown> = {}
  for (const [key, item] of Object.entries(value)) {
    // Parsed receipts retain qualified power_integral, never raw sample arrays or upstream identities/secrets.
    const normalizedKey = key.replace(/_/g, '').toLowerCase()
    if (['vin', 'accesstoken', 'refreshtoken', 'sessionkey', 'appsecret', 'privatekey', 'clientsecret', 'authorization',
      'password', 'clientproof', 'linktoken', 'drivedetails'].includes(normalizedKey)) continue
    if (['positions', 'drive_positions', 'charge_details', 'chargePoints', 'route', 'rawPositions',
      'startLatitude', 'startLongitude', 'endLatitude', 'endLongitude', 'latitude', 'longitude',
      'start_latitude', 'start_longitude', 'end_latitude', 'end_longitude', 'lat', 'lon', 'lng'].includes(key)) continue
    result[key] = cacheProjection(item, depth + 1)
  }
  return result
}
function boundedItems<T>(key: string, rows: readonly T[], savedAt: string): T[] {
  const result: T[] = []
  let bytes = historyUtf8Bytes(JSON.stringify({ schema: HISTORY_CACHE_SCHEMA, scope: key, items: [], savedAt }))
  const latest = [...rows].sort((a, b) => {
    const date = (row: T) => { const value = (row as { startDate?: unknown })?.startDate; const time = typeof value === 'string' ? Date.parse(value) : Number.NaN; return Number.isFinite(time) ? time : Number.NEGATIVE_INFINITY }
    const left = date(a), right = date(b)
    return left === right ? 0 : left < right ? 1 : -1
  })
  for (const row of latest.slice(0, HISTORY_CACHE_MAX_ROWS)) {
    const projected = cacheProjection(row) as T
    const serialized = JSON.stringify(projected)
    if (serialized === undefined) continue
    const additional = historyUtf8Bytes(serialized) + (result.length > 0 ? 1 : 0)
    if (bytes + additional > HISTORY_CACHE_MAX_BYTES) continue
    bytes += additional
    result.push(projected)
  }
  return result
}

export function readHistoryCache<T>(storage: Pick<WechatStorage, 'getStorageSync' | 'setStorageSync'>, key: string): T[] {
  if (key.includes('.anonymous.')) return []
  try {
    const value = storage.getStorageSync(key) as Partial<HistoryCache<T>> | undefined
    if (value && value.schema === HISTORY_CACHE_SCHEMA && value.scope === key && Array.isArray(value.items)) return boundedItems(key, value.items, value.savedAt ?? new Date().toISOString())
    const storageInfo = storage as Pick<WechatStorage, 'getStorageInfoSync'>
    if (typeof storageInfo.getStorageInfoSync !== 'function') return []
    const legacyPrefix = key.replace(`.v${HISTORY_CACHE_SCHEMA}.`, `.v${PREVIOUS_HISTORY_CACHE_SCHEMA}.`) + '.'
    const legacyItems: T[] = []
    for (const legacyKey of storageInfo.getStorageInfoSync().keys ?? []) {
      if (!legacyKey.startsWith(legacyPrefix) || !/^\d+$/.test(legacyKey.slice(legacyPrefix.length))) continue
      const legacy = storage.getStorageSync(legacyKey) as Partial<HistoryCache<T>> | undefined
      if (!legacy || legacy.schema !== PREVIOUS_HISTORY_CACHE_SCHEMA || legacy.scope !== legacyKey || !Array.isArray(legacy.items)) continue
      legacyItems.push(...legacy.items)
    }
    if (legacyItems.length === 0) return []
    const merged = mergeHistoryByKey([], legacyItems, item => {
      const row = item as T & { id?: string; sessionId?: string | null; startDate?: string | null }
      return row.id ? historyRowKey({ id: row.id, sessionId: row.sessionId, startDate: row.startDate }) : JSON.stringify(row)
    }).items
    writeHistoryCache(storage, key, merged)
    return boundedItems(key, merged, new Date().toISOString())
  } catch {
    return []
  }
}

export function writeHistoryCache<T>(storage: Pick<WechatStorage, 'setStorageSync'> & Partial<Pick<WechatStorage, 'getStorageSync' | 'getStorageInfoSync'>>, key: string, items: readonly T[]): boolean {
  if (key.includes('.anonymous.')) return false
  try {
    const savedAt = new Date().toISOString()
    const cache: HistoryCache<T> = { schema: HISTORY_CACHE_SCHEMA, scope: key, items: boundedItems(key, items, savedAt), savedAt }
    if (historyUtf8Bytes(JSON.stringify(cache)) > HISTORY_CACHE_MAX_BYTES) return false
    if (storage.getStorageInfoSync && !canStoreCache(storage, key, cache)) return false
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

export function clearHistoryCachesForAccount(
  storage: Pick<WechatStorage, 'getStorageInfoSync' | 'removeStorageSync'>,
  userId: string,
  apiOrigin: string,
): void {
  if (!userId.trim() || !apiOrigin.startsWith('https://')) return
  const owner = `${encodeScope(apiOrigin)}.${encodeScope(userId)}.`
  try {
    for (const key of storage.getStorageInfoSync?.().keys ?? []) {
      for (const schema of [HISTORY_CACHE_SCHEMA, PREVIOUS_HISTORY_CACHE_SCHEMA]) {
        const prefix = `matelink.history.v${schema}.${owner}`
        if (!key.startsWith(prefix)) continue
        // Legacy keys leave dots unescaped. Preserve ambiguous identities rather
        // than risk deleting another account's archive.
        const suffix = key.slice(prefix.length)
        const valid = schema === HISTORY_CACHE_SCHEMA
          ? /^[^.]+\.(drives|charges)(?:\.query)?$/.test(suffix)
          : /^[^.]+\.(drives|charges)\.\d+$/.test(suffix)
        if (valid) storage.removeStorageSync(key)
      }
    }
  } catch {
    // Server-side account deletion remains authoritative if optional local cache cleanup fails.
  }
}
