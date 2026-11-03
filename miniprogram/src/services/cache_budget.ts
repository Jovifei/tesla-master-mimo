import type { WechatStorage } from './session'

// Total allowance is unchanged; new replaceable caches reserve capacity for user data.
export const CACHE_BUDGET_BYTES = 800 * 1024
export const USER_DATA_RESERVE_BYTES = 128 * 1024
type BudgetStorage = Partial<Pick<WechatStorage, 'getStorageSync' | 'getStorageInfoSync'>>
export type LocalStorageUsage = { totalBytes: number; userBytes: number; cacheBytes: number; budgetBytes: number; reservedUserBytes: number }
export function isProtectedLocalKey(key: string): boolean {
  return ['matelink.charge-cost.', 'matelink.manual-trips.', 'matelink.tariff.',
    'matelink.tpms-profile.', 'matelink.vehicle-appearance.', 'matelink.wechat.',
    'matelink.selected.car.', 'matelink.legal.'].some(prefix => key.startsWith(prefix))
}
export function cacheBytes(value: unknown): number {
  const text = JSON.stringify(value)
  if (text === undefined) return 0
  let size = 0
  for (let index = 0; index < text.length; index++) {
    const code = text.charCodeAt(index)
    if (code < 128) size++
    else if (code < 2048) size += 2
    else if (code >= 0xd800 && code <= 0xdbff && text.charCodeAt(index + 1) >= 0xdc00 && text.charCodeAt(index + 1) <= 0xdfff) { size += 4; index++ }
    else size += 3
  }
  return size
}
/** Counts only: no account keys, records, locations or credentials leave this projection. */
export function localStorageUsage(storage: BudgetStorage): LocalStorageUsage | null {
  if (!storage.getStorageInfoSync || !storage.getStorageSync) return null
  try {
    const keys = storage.getStorageInfoSync().keys
    if (!Array.isArray(keys)) return null
    let userBytes = 0, caches = 0
    for (const key of new Set(keys)) {
      if (typeof key !== 'string' || !key.startsWith('matelink.')) continue
      const size = cacheBytes(key) + cacheBytes(storage.getStorageSync(key))
      if (isProtectedLocalKey(key)) userBytes += size
      else caches += size
    }
    return { totalBytes: userBytes + caches, userBytes, cacheBytes: caches,
      budgetBytes: CACHE_BUDGET_BYTES, reservedUserBytes: USER_DATA_RESERVE_BYTES }
  } catch { return null }
}
export function canStoreCache(storage: BudgetStorage, key: string, value: unknown): boolean {
  if (!storage.getStorageInfoSync || !storage.getStorageSync || !key.startsWith('matelink.')) return false
  try {
    const usage = localStorageUsage(storage)
    if (!usage) return false
    const previous = storage.getStorageSync(key)
    const before = previous == null || previous === '' ? 0 : cacheBytes(previous) + cacheBytes(key)
    const after = cacheBytes(value) + cacheBytes(key)
    const total = usage.totalBytes - before + after
    const userWrite = isProtectedLocalKey(key)
    const users = usage.userBytes + (userWrite ? after - before : 0)
    const caches = total - users
    // Existing over-budget archives are retained. A correction that does not
    // grow an existing key is safe for user records too (including a true-zero
    // fee edit or explicit removal). Never evict another archive to admit it.
    if (before > 0 && after <= before) return true
    return total <= CACHE_BUDGET_BYTES &&
      (userWrite || caches <= CACHE_BUDGET_BYTES - Math.max(USER_DATA_RESERVE_BYTES, users))
  } catch { return false }
}
