import { validTpmsProfile, type TpmsProfile } from '../domain/tpms_alerts'
import { canStoreCache, cacheBytes } from './cache_budget'
import type { WechatStorage } from './session'

export type TpmsProfileScope = { origin: string; userId: string; stableId: string }
type Storage = Pick<WechatStorage, 'getStorageSync' | 'setStorageSync' | 'getStorageInfoSync'>
export const TPMS_PROFILE_MAX_BYTES = 4096
export function tpmsProfileKey(scope: TpmsProfileScope): string | null {
  if (![scope.userId, scope.stableId].every(value => typeof value === 'string' && value.trim() && value.length <= 256) || !/^https:\/\/[a-z0-9.-]+(?::443)?$/i.test(scope.origin)) return null
  return `matelink.tpms-profile.v1.${[scope.origin, scope.userId, scope.stableId].map(value => encodeURIComponent(value).replace(/\./g, '%2E')).join('.')}`
}
function project(value: TpmsProfile): TpmsProfile { return { enabled: value.enabled, targetBar: value.targetBar, lowBar: value.lowBar, highBar: value.highBar, changeEnabled: value.changeEnabled, deltaBar: value.deltaBar, windowHours: value.windowHours } }
function decode(value: unknown, key: string): TpmsProfile | null {
  if (!value || typeof value !== 'object' || Array.isArray(value) || cacheBytes(value) > TPMS_PROFILE_MAX_BYTES) return null
  const row = value as { schema?: unknown; scope?: unknown; source?: unknown; profile?: unknown }
  return row.schema === 1 && row.scope === key && row.source === 'user_configured' && validTpmsProfile(row.profile) ? project(row.profile) : null
}
export function readTpmsProfile(storage: Pick<WechatStorage, 'getStorageSync'>, scope: TpmsProfileScope): TpmsProfile | null {
  const key = tpmsProfileKey(scope)
  if (!key) return null
  try { return decode(storage.getStorageSync(key), key) } catch { return null }
}
export function writeTpmsProfile(storage: Storage, scope: TpmsProfileScope, profile: TpmsProfile, isCurrent: () => boolean): { written: boolean; reason: string | null } {
  const key = tpmsProfileKey(scope)
  if (!key || !validTpmsProfile(profile)) return { written: false, reason: 'invalid_profile' }
  try {
    if (!isCurrent()) return { written: false, reason: 'scope_changed' }
    const previous = storage.getStorageSync(key)
    if (previous != null && previous !== '' && !decode(previous, key)) return { written: false, reason: 'ambiguous_archive' }
    const value = { schema: 1, scope: key, source: 'user_configured', profile: project(profile) }
    if (cacheBytes(value) > TPMS_PROFILE_MAX_BYTES || !canStoreCache(storage, key, value)) return { written: false, reason: 'cache_budget' }
    if (!isCurrent()) return { written: false, reason: 'scope_changed' }
    storage.setStorageSync(key, value)
    return { written: true, reason: null }
  } catch { return { written: false, reason: 'storage_unavailable' } }
}
