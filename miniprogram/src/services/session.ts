import { MOCK_FIXTURE_ENABLED, MOCK_USER_ID } from './mock_fixture'

const SESSION_KEY = 'matelink.wechat.session.v1'
const PENDING_LINK_KEY = 'matelink.wechat.link.v1'
const PENDING_TESLA_AUTH_KEY = 'matelink.wechat.tesla-auth.v1'
const WECHAT_RECOVERY_KEY_PREFIX = 'matelink.wechat.recovery.v1.'

export type AppSession = {
  apiOrigin?: string
  accessToken: string
  refreshToken: string | null
  expiresAt: string | null
  userId: string | null
  linkRequired: boolean | null
  // Missing on older sessions, which were all established through Tesla.
  source?: 'wechat' | 'tesla'
}

function currentOrigin(): string | null {
  const value = process.env.TARO_APP_API_BASE_URL?.replace(/\/$/, '')
  return value?.startsWith('https://') ? value : null
}

function matchesOrigin(value: unknown): value is string {
  const current = currentOrigin()
  return current !== null && typeof value === 'string' && value === current
}

export function readAppSession(storage: Pick<WechatStorage, 'getStorageSync'>): AppSession | null {
  // Review fixture: a stable local session so pages render synthetic data
  // without a Tesla login. Only active when the build enables the fixture.
  if (MOCK_FIXTURE_ENABLED) {
    return { apiOrigin: 'https://api.teslalink.joviluma.com', accessToken: 'mock-fixture-token',
      refreshToken: null, expiresAt: null, userId: MOCK_USER_ID, linkRequired: false }
  }
  const value = storage.getStorageSync(SESSION_KEY) as Partial<AppSession> | undefined
  if (!value || !matchesOrigin(value.apiOrigin) || typeof value.accessToken !== 'string' || value.accessToken.length === 0) return null
  return {
    apiOrigin: value.apiOrigin,
    accessToken: value.accessToken,
    refreshToken: typeof value.refreshToken === 'string' && value.refreshToken.length > 0 ? value.refreshToken : null,
    expiresAt: typeof value.expiresAt === 'string' ? value.expiresAt : null,
    userId: typeof value.userId === 'string' && value.userId.length > 0 ? value.userId : null,
    linkRequired: typeof value.linkRequired === 'boolean' ? value.linkRequired : null,
    ...(value.source === 'wechat' ? { source: 'wechat' as const } : {}),
  }
}

export type WechatRecoveryPreference = { apiOrigin: string; boundUserId: string; enabled: boolean }

function recoveryKey(origin: string): string { return `${WECHAT_RECOVERY_KEY_PREFIX}${encodeURIComponent(origin)}` }

export function readWechatRecoveryPreference(storage: Pick<WechatStorage, 'getStorageSync'>, origin: string): WechatRecoveryPreference | null {
  if (!matchesOrigin(origin)) return null
  try {
    const value = storage.getStorageSync(recoveryKey(origin)) as Partial<WechatRecoveryPreference> | undefined
    if (!value || value.apiOrigin !== origin || typeof value.boundUserId !== 'string' || !value.boundUserId.trim() || typeof value.enabled !== 'boolean') return null
    return { apiOrigin: origin, boundUserId: value.boundUserId, enabled: value.enabled }
  } catch { return null }
}

export function writeWechatRecoveryPreference(storage: Pick<WechatStorage, 'setStorageSync'>, value: WechatRecoveryPreference): void {
  if (!matchesOrigin(value.apiOrigin) || !value.boundUserId.trim() || typeof value.enabled !== 'boolean') throw new Error('Invalid recovery preference')
  storage.setStorageSync(recoveryKey(value.apiOrigin), { apiOrigin: value.apiOrigin, boundUserId: value.boundUserId, enabled: value.enabled })
}

export function clearWechatRecoveryPreference(storage: Pick<WechatStorage, 'removeStorageSync'>, origin: string): void {
  storage.removeStorageSync(recoveryKey(origin))
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
  getStorageInfoSync?: () => { keys?: string[] }
}

export type PendingWechatLink = {
  apiOrigin?: string
  linkToken: string
  expiresAt: string | null
}

export function readPendingWechatLink(storage: Pick<WechatStorage, 'getStorageSync'>): PendingWechatLink | null {
  const value = storage.getStorageSync(PENDING_LINK_KEY) as Partial<PendingWechatLink> | undefined
  if (!value || !matchesOrigin(value.apiOrigin) || typeof value.linkToken !== 'string' || value.linkToken.trim() === '') return null
  return {
    apiOrigin: value.apiOrigin,
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
  apiOrigin: string
  expiresAt: string | null
}

export function readPendingTeslaAuthorization(storage: Pick<WechatStorage, 'getStorageSync'>): PendingTeslaAuthorization | null {
  const value = storage.getStorageSync(PENDING_TESLA_AUTH_KEY) as Partial<PendingTeslaAuthorization> | undefined
  if (!value || typeof value.transactionId !== 'string' || value.transactionId.trim() === '' || typeof value.clientProof !== 'string' || value.clientProof.trim() === '' || typeof value.apiOrigin !== 'string' || !value.apiOrigin.startsWith('https://')) return null
  return {
    transactionId: value.transactionId,
    clientProof: value.clientProof,
    apiOrigin: value.apiOrigin,
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
