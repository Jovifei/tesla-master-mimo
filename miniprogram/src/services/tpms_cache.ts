import { TPMS_RETENTION_MS, TPMS_WHEELS, tpmsInstant, tpmsPoints, type TpmsPoint, type TpmsSnapshot, type TpmsWheel } from '../domain/tpms'
import { historyUtf8Bytes } from './history_cache'
import { canStoreCache } from './cache_budget'
import type { WechatStorage } from './session'

export const TPMS_CACHE_MAX_POINTS_PER_WHEEL = 500
export const TPMS_CACHE_MAX_BYTES = 256 * 1024
const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
const SOURCE = 'telemetry_mqtt'
export type TpmsHistory = { points: TpmsPoint[]; limited: boolean; oldestAt: string | null; latestAt: string | null }
type CacheEnvelope = { schema: 1; scope: string; points: TpmsPoint[]; limited: boolean }
type Storage = Pick<WechatStorage, 'getStorageSync' | 'setStorageSync' | 'getStorageInfoSync'>
const empty = (): TpmsHistory => ({ points: [], limited: false, oldestAt: null, latestAt: null })

export function tpmsCacheKey(user: string, origin: string, uid: string): string | null {
  if (![user, uid].every(value => value.trim() && value.length <= 256) || !/^https:\/\/[a-z0-9.-]+(?::\d{1,5})?$/i.test(origin)) return null
  return `matelink.tpms.v1.${[origin, user, uid, SOURCE].map(encode).join('.')}`
}
function point(value: unknown, now: number): TpmsPoint | null {
  if (!value || typeof value !== 'object') return null
  const row = value as Record<string, unknown>
  const at = tpmsInstant(row.observedAt)
  if (!TPMS_WHEELS.includes(row.wheel as TpmsWheel) || row.source !== SOURCE || row.timeBasis !== 'collector_received_at' ||
    typeof row.pressureBar !== 'number' || !Number.isFinite(row.pressureBar) || row.pressureBar < 0 || row.pressureBar > 15 ||
    at == null || at > now || at < now - TPMS_RETENTION_MS) return null
  return { wheel: row.wheel as TpmsWheel, pressureBar: row.pressureBar, observedAt: new Date(at).toISOString(), source: SOURCE, timeBasis: 'collector_received_at' }
}
function envelope(scope: string, points: unknown[], previousLimited: boolean, now: number): CacheEnvelope {
  const byKey = new Map<string, TpmsPoint>()
  let limited = previousLimited
  for (const raw of points) {
    const row = point(raw, now)
    if (row) byKey.set(`${row.wheel}:${row.observedAt}`, row)
    else limited = true
  }
  const counts = { fl: 0, fr: 0, rl: 0, rr: 0 }
  const selected = [...byKey.values()].sort((a, b) => Date.parse(b.observedAt) - Date.parse(a.observedAt)).filter(row => {
    counts[row.wheel] += 1
    if (counts[row.wheel] <= TPMS_CACHE_MAX_POINTS_PER_WHEEL) return true
    limited = true
    return false
  })
  const result: CacheEnvelope = { schema: 1, scope, points: selected, limited }
  // At most 2,000 points. Drop oldest receipt rather than exceed the per-scope byte ceiling.
  while (result.points.length && historyUtf8Bytes(JSON.stringify(result)) > TPMS_CACHE_MAX_BYTES) {
    result.points.pop()
    result.limited = true
  }
  return result
}
function view(value: CacheEnvelope): TpmsHistory {
  const points = [...value.points].sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt))
  return { points, limited: value.limited, oldestAt: points[0]?.observedAt ?? null, latestAt: points[points.length - 1]?.observedAt ?? null }
}
function read(storage: Pick<WechatStorage, 'getStorageSync'>, key: string, now: number): CacheEnvelope {
  try {
    const value = storage.getStorageSync(key) as Partial<CacheEnvelope> | null
    if (value?.schema === 1 && value.scope === key && Array.isArray(value.points)) return envelope(key, value.points, value.limited === true, now)
  } catch { /* Unreadable local history is unknown, never evidence of zero pressure. */ }
  return envelope(key, [], false, now)
}
export function readTpmsCache(storage: Pick<WechatStorage, 'getStorageSync'>, user: string, origin: string, uid: string, now = Date.now()): TpmsHistory {
  const key = tpmsCacheKey(user, origin, uid)
  return key ? view(read(storage, key, now)) : empty()
}
export function observeTpmsCache(storage: Storage, user: string, origin: string, uid: string, snapshot: TpmsSnapshot, now = Date.now()): { history: TpmsHistory; written: boolean; reason: string | null } {
  const key = tpmsCacheKey(user, origin, uid)
  if (!key) return { history: empty(), written: false, reason: 'invalid_scope' }
  try {
    const raw = storage.getStorageSync(key)
    if (raw != null && raw !== '') {
      const existing = raw as Partial<CacheEnvelope>
      if (typeof raw !== 'object' || Array.isArray(raw) || existing.schema !== 1 || existing.scope !== key || !Array.isArray(existing.points))
        return { history: empty(), written: false, reason: 'ambiguous_archive' }
    }
  } catch { return { history: empty(), written: false, reason: 'storage_unavailable' } }
  const previous = read(storage, key, now)
  const observed = tpmsPoints(snapshot, now)
  if (!observed.length) return { history: view(previous), written: false, reason: 'no_qualified_observation' }
  // A repeated old receipt never becomes a newly timestamped sample.
  const merged = envelope(key, [...previous.points, ...observed], previous.limited, now)
  if (!canStoreCache(storage, key, merged)) return { history: view(previous), written: false, reason: 'cache_budget' }
  try {
    storage.setStorageSync(key, merged)
    return { history: view(merged), written: true, reason: null }
  } catch { return { history: view(previous), written: false, reason: 'storage_unavailable' } }
}
export function clearTpmsCachesForAccount(storage: Pick<WechatStorage, 'getStorageInfoSync' | 'removeStorageSync'>, user: string, origin: string): void {
  if (!tpmsCacheKey(user, origin, 'scope-check')) return
  const prefix = `matelink.tpms.v1.${[origin, user].map(encode).join('.')}.`
  try { for (const key of storage.getStorageInfoSync?.().keys ?? []) if (key.startsWith(prefix)) storage.removeStorageSync(key) } catch { /* Keep unavailable storage intact. */ }
}
