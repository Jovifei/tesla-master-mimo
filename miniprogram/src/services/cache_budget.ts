import type { WechatStorage } from './session'

// New foreground capabilities share the pre-existing largest cache allowance.
// Admission never evicts an Owner archive or changes WeChat's storage quota.
export const CACHE_BUDGET_BYTES = 800 * 1024
type BudgetStorage = Partial<Pick<WechatStorage, 'getStorageSync' | 'getStorageInfoSync'>>

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

export function canStoreCache(storage: BudgetStorage, key: string, value: unknown): boolean {
  if (!storage.getStorageInfoSync || !storage.getStorageSync) return false
  try {
    let bytes = cacheBytes(value) + cacheBytes(key)
    const keys = storage.getStorageInfoSync().keys
    if (!Array.isArray(keys)) return false
    for (const existing of new Set(keys)) {
      if (existing === key || !existing.startsWith('matelink.')) continue
      bytes += cacheBytes(existing) + cacheBytes(storage.getStorageSync(existing))
      if (bytes > CACHE_BUDGET_BYTES) return false
    }
    return bytes <= CACHE_BUDGET_BYTES
  } catch { return false }
}
