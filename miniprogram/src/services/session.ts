const SESSION_KEY = 'matelink.wechat.session.v1'

export type AppSession = {
  accessToken: string
  expiresAt: string | null
}

export function readAppSession(storage: Pick<WechatStorage, 'getStorageSync'>): AppSession | null {
  const value = storage.getStorageSync(SESSION_KEY) as Partial<AppSession> | undefined
  if (!value || typeof value.accessToken !== 'string' || value.accessToken.length === 0) return null
  return { accessToken: value.accessToken, expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : null }
}

export function writeAppSession(storage: Pick<WechatStorage, 'setStorageSync'>, session: AppSession): void {
  storage.setStorageSync(SESSION_KEY, session)
}

export function clearAppSession(storage: Pick<WechatStorage, 'removeStorageSync'>): void {
  storage.removeStorageSync(SESSION_KEY)
}

export type WechatStorage = {
  getStorageSync(key: string): unknown
  setStorageSync(key: string, value: unknown): void
  removeStorageSync(key: string): void
}
