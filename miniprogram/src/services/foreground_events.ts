import type { Charge, Drive } from './types'
import type { WechatStorage } from './session'
import { cacheBytes, canStoreCache } from './cache_budget'

export type ForegroundScope = { origin: string; userId: string; stableId: string }
export type ForegroundNotice = {
  kind: 'drives' | 'charges'; id: string; sessionId: string | null; source: string; startDate: string; endDate: string
}
type SourceJournal = { baseline: number; watermark: number; seen: string[] }
type Journal = { schema: 1; scope: string; sources: Record<string, SourceJournal>; emptySince?: number }
const encode = (text: string) => encodeURIComponent(text).replace(/\./g, '%2E')
const MAX_SEEN = 500, MAX_BYTES = 64 * 1024
const verifiedSources = new Set(['telemetry_mqtt', 'teslamate', 'teslamate_archive', 'fleet_native', 'fleet_telemetry', 'fleet_telemetry_native', 'legacy_api', 'api'])
const observationErrors = new WeakMap<object, Map<string, string>>()
function observationFailure(storage: object, key: string, reason: string): ForegroundNotice[] {
  const errors = observationErrors.get(storage) || new Map<string, string>()
  errors.set(key, reason)
  if (errors.size > 32) errors.delete(errors.keys().next().value!)
  observationErrors.set(storage, errors)
  return []
}
export function inspectForegroundEvents(storage: Pick<WechatStorage, 'getStorageSync'>, scope: ForegroundScope, kind: 'drives' | 'charges'): { limited: boolean; tracked: number; reason: string | null } {
  try {
    const failure = observationErrors.get(storage)?.get(foregroundEventsKey(scope, kind))
    if (failure) return { limited: true, tracked: 0, reason: failure }
    const journal = storage.getStorageSync(foregroundEventsKey(scope, kind)) as Journal | undefined
    if (!journal) return { limited: false, tracked: 0, reason: null }
    if (journal.schema !== 1 || journal.scope !== foregroundEventsKey(scope, kind) || !journal.sources) return { limited: true, tracked: 0, reason: 'invalid_journal' }
    const tracked = Object.values(journal.sources).reduce((count, source) => count + source.seen.length, 0)
    return { limited: tracked >= MAX_SEEN || Object.keys(journal.sources).length >= 8 || cacheBytes(journal) >= MAX_BYTES - 600,
      tracked, reason: null }
  } catch { return { limited: true, tracked: 0, reason: 'storage_unavailable' } }
}
export function foregroundEventsKey(scope: ForegroundScope, kind: 'drives' | 'charges'): string {
  return `matelink.events.v1.${encode(scope.origin)}.${encode(scope.userId)}.${encode(scope.stableId)}.${kind}`
}

/** Called only for an accepted current first page. No platform notification is sent.
 * A full/unreadable journal suppresses delivery; it never sacrifices durable dedup.
 */
export function observeForegroundEvents(storage: Pick<WechatStorage, 'getStorageSync' | 'setStorageSync' | 'getStorageInfoSync'>,
  scope: ForegroundScope, kind: 'drives' | 'charges', rows: readonly (Drive | Charge)[], now = Date.now()): ForegroundNotice[] {
  if (!/^https:\/\/[a-z0-9.-]+(?::443)?\/?$/i.test(scope.origin) || !scope.userId.trim() || !scope.stableId.trim()) return []
  const key = foregroundEventsKey(scope, kind)
  try {
    const previous = storage.getStorageSync(key) as Journal | undefined
    if (previous && (previous.schema !== 1 || previous.scope !== key || !previous.sources || typeof previous.sources !== 'object')) return observationFailure(storage, key, 'invalid_journal')
    const journal: Journal = previous ? JSON.parse(JSON.stringify(previous)) : { schema: 1, scope: key, sources: {} }
    const qualified = rows.filter(row => row.source && verifiedSources.has(row.source) && row.id && row.id.length <= 256 &&
      (row.qualityState === 'observed' || row.qualityState === 'derived') && row.startDate && row.endDate &&
      Number.isFinite(Date.parse(row.startDate)) && Date.parse(row.endDate) >= Date.parse(row.startDate) && Date.parse(row.endDate) <= now)
    const notices: ForegroundNotice[] = []
    if (!previous && rows.length === 0) journal.emptySince = now
    for (const source of [...new Set(qualified.map(row => row.source!))]) {
      const sourceKey = encode(source)
      if (!Object.prototype.hasOwnProperty.call(journal.sources, sourceKey)) {
        if (Object.keys(journal.sources).length >= 8) continue
        const baseline = Number.isFinite(journal.emptySince) ? journal.emptySince! : now
        journal.sources[sourceKey] = { baseline, watermark: baseline, seen: [] }
        delete journal.emptySince
      }
      const state = journal.sources[sourceKey]
      if (!state || !Number.isFinite(state.baseline) || !Number.isFinite(state.watermark) || !Array.isArray(state.seen) || state.seen.some(id => typeof id !== 'string')) return observationFailure(storage, key, 'invalid_journal')
      const seen = new Set(state.seen)
      const candidates = qualified.filter(row => row.source === source).sort((a, b) => Date.parse(a.endDate!) - Date.parse(b.endDate!))
      for (const row of candidates) {
        // Dates are mutable corrections, never legacy identities.
        const identity = row.sessionId ? `session:${row.sessionId}` : `id:${row.id}`
        if (identity.length > 300 || seen.has(identity)) continue
        if (Object.values(journal.sources).reduce((count, entry) => count + entry.seen.length, 0) >= MAX_SEEN) continue
        const end = Date.parse(row.endDate!)
        if (end > state.watermark) notices.push({ kind, id: row.id, sessionId: row.sessionId, source, startDate: row.startDate!, endDate: row.endDate! })
        seen.add(identity)
        state.seen.push(identity)
      }
      state.watermark = Math.max(state.watermark, ...candidates.map(row => Date.parse(row.endDate!)))
    }
    if (cacheBytes(journal) > MAX_BYTES || !canStoreCache(storage, key, journal)) return observationFailure(storage, key, 'cache_budget')
    storage.setStorageSync(key, journal)
    observationErrors.get(storage)?.delete(key)
    return notices
  } catch { return observationFailure(storage, key, 'storage_unavailable') }
}
