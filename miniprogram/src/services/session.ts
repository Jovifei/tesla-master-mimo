const SESSION_KEY = 'matelink.wechat.session.v1'
const PENDING_LINK_KEY = 'matelink.wechat.link.v1'
const PENDING_TESLA_AUTH_KEY = 'matelink.wechat.tesla-auth.v1'

export type AppSession = {
  accessToken: string
  refreshToken: string | null
  expiresAt: string | null
  userId: string | null
  linkRequired: boolean | null
}

export function readAppSession(storage: Pick<WechatStorage, 'getStorageSync'>): AppSession | null {
  const value = storage.getStorageSync(SESSION_KEY) as Partial<AppSession> | undefined
  if (!value || typeof value.accessToken !== 'string' || value.accessToken.length === 0) return null
  return {
    accessToken: value.accessToken,
    refreshToken: typeof value.refreshToken === 'string' && value.refreshToken.length > 0 ? value.refreshToken : null,
    expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : null,
    userId: typeof value.userId === 'string' && value.userId.length > 0 ? value.userId : null,
    linkRequired: typeof value.linkRequired === 'boolean' ? value.linkRequired : null,
  }
}

export function writeAppSession(storage: Pick<WechatStorage, 'setStorageSync'>, session: AppSession): void {
  storage.setStorageSync(SESSION_KEY, session)
}

export function clearAppSession(storage: Pick<WechatStorage, 'removeStorageSync'>): void {
  storage.removeStorageSync(SESSION_KEY)
  storage.removeStorageSync(PENDING_LINK_KEY)
  storage.removeStorageSync(PENDING_TESLA_AUTH_KEY)
}

export type WechatStorage = {
  getStorageSync(key: string): unknown
  setStorageSync(key: string, value: unknown): void
  removeStorageSync(key: string): void
}

export type PendingWechatLink = {
  linkToken: string
  expiresAt: string | null
}

export function readPendingWechatLink(storage: Pick<WechatStorage, 'getStorageSync'>): PendingWechatLink | null {
  const value = storage.getStorageSync(PENDING_LINK_KEY) as Partial<PendingWechatLink> | undefined
  if (!value || typeof value.linkToken !== 'string' || value.linkToken.trim() === '') return null
  return {
    linkToken: value.linkToken,
    expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : null,
  }
}

export function writePendingWechatLink(storage: Pick<WechatStorage, 'setStorageSync'>, link: PendingWechatLink): void {
  storage.setStorageSync(PENDING_LINK_KEY, link)
}

export function clearPendingWechatLink(storage: Pick<WechatStorage, 'removeStorageSync'>): void {
  storage.removeStorageSync(PENDING_LINK_KEY)
}

export type PendingTeslaAuthorization = {
  transactionId: string
  clientProof: string
  expiresAt: string | null
}

export function readPendingTeslaAuthorization(storage: Pick<WechatStorage, 'getStorageSync'>): PendingTeslaAuthorization | null {
  const value = storage.getStorageSync(PENDING_TESLA_AUTH_KEY) as Partial<PendingTeslaAuthorization> | undefined
  if (!value || typeof value.transactionId !== 'string' || value.transactionId.trim() === '' || typeof value.clientProof !== 'string' || value.clientProof.trim() === '') return null
  return {
    transactionId: value.transactionId,
    clientProof: value.clientProof,
    expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : null,
  }
}

export function writePendingTeslaAuthorization(storage: Pick<WechatStorage, 'setStorageSync'>, pending: PendingTeslaAuthorization): void {
  storage.setStorageSync(PENDING_TESLA_AUTH_KEY, pending)
}

export function clearPendingTeslaAuthorization(storage: Pick<WechatStorage, 'removeStorageSync'>): void {
  storage.removeStorageSync(PENDING_TESLA_AUTH_KEY)
}

export { SESSION_KEY, PENDING_LINK_KEY, PENDING_TESLA_AUTH_KEY }
