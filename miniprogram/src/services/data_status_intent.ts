import type { WechatStorage } from './session'

const KEY = 'matelink.ui.data-status.v1'

/** One-use navigation hint; it carries no vehicle location or session token. */
export function requestDataStatus(storage: Pick<WechatStorage, 'setStorageSync'>, userId: string, origin: string, now = Date.now()): void {
  storage.setStorageSync(KEY, { userId, origin, at: now })
}

export function consumeDataStatus(storage: Pick<WechatStorage, 'getStorageSync' | 'removeStorageSync'>, userId: string | null, origin: string, now = Date.now()): boolean {
  const value = storage.getStorageSync(KEY) as { userId?: unknown; origin?: unknown; at?: unknown } | null
  storage.removeStorageSync(KEY)
  return Boolean(userId && value && value.userId === userId && value.origin === origin &&
    typeof value.at === 'number' && Number.isFinite(value.at) && value.at <= now && now - value.at < 30_000)
}
