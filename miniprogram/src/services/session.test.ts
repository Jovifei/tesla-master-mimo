import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearAppSession,
  clearPendingTeslaAuthorization,
  readAppSession,
  readPendingWechatLink,
  readPendingTeslaAuthorization,
  readWechatRecoveryPreference,
  writeAppSession,
  writePendingWechatLink,
  writePendingTeslaAuthorization,
  writeWechatRecoveryPreference,
} from './session'

function storage() {
  const values = new Map<string, unknown>()
  return {
    values,
    getStorageSync: (key: string) => values.get(key),
    setStorageSync: (key: string, value: unknown) => values.set(key, value),
    removeStorageSync: (key: string) => values.delete(key),
  }
}

beforeEach(() => vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test'))

describe('session storage', () => {
  it('round trips rotating tokens and account/link state', () => {
    const store = storage()
    writeAppSession(store, {
      apiOrigin: 'https://api.example.test',
      accessToken: 'access',
      refreshToken: 'refresh',
      expiresAt: '2026-09-13T00:00:00Z',
      userId: 'user-a',
      linkRequired: false,
    })
    expect(readAppSession(store)).toEqual({
      apiOrigin: 'https://api.example.test',
      accessToken: 'access',
      refreshToken: 'refresh',
      expiresAt: '2026-09-13T00:00:00Z',
      userId: 'user-a',
      linkRequired: false,
    })
  })

  it('stores the short lived link token separately and clears it on logout', () => {
    const store = storage()
    writePendingWechatLink(store, { apiOrigin: 'https://api.example.test', linkToken: 'link-token', expiresAt: null })
    expect(readPendingWechatLink(store)).toEqual({ apiOrigin: 'https://api.example.test', linkToken: 'link-token', expiresAt: null })
    clearAppSession(store)
    expect(readPendingWechatLink(store)).toBeNull()
  })

  it('stores only the opaque Tesla transaction proof and clears it with the session', () => {
    const store = storage()
    writePendingTeslaAuthorization(store, { transactionId: 'txn-1', clientProof: 'proof-1', apiOrigin: 'https://api.example.test', expiresAt: null })
    expect(readPendingTeslaAuthorization(store)).toEqual({ transactionId: 'txn-1', clientProof: 'proof-1', apiOrigin: 'https://api.example.test', expiresAt: null })
    clearPendingTeslaAuthorization(store)
    expect(readPendingTeslaAuthorization(store)).toBeNull()
    writePendingTeslaAuthorization(store, { transactionId: 'txn-2', clientProof: 'proof-2', apiOrigin: 'https://api.example.test', expiresAt: null })
    clearAppSession(store)
    expect(readPendingTeslaAuthorization(store)).toBeNull()
  })

  it('rejects malformed sessions without an access token', () => {
    const store = storage()
    store.setStorageSync('matelink.wechat.session.v1', { refreshToken: 'refresh' })
    expect(readAppSession(store)).toBeNull()
  })

  it('isolates and validates the non-secret recovery preference per API origin', () => {
    const store = storage()
    const first = { apiOrigin: 'https://api.example.test', boundUserId: 'a', enabled: true }
    writeWechatRecoveryPreference(store, first)
    expect(readWechatRecoveryPreference(store, first.apiOrigin)).toEqual(first)
    vi.stubEnv('TARO_APP_API_BASE_URL', 'https://other.example.test')
    expect(readWechatRecoveryPreference(store, first.apiOrigin)).toBeNull()
    expect(readWechatRecoveryPreference(store, 'https://other.example.test')).toBeNull()
    writeWechatRecoveryPreference(store, { apiOrigin: 'https://other.example.test', boundUserId: 'b', enabled: false })
    vi.stubEnv('TARO_APP_API_BASE_URL', first.apiOrigin)
    expect(readWechatRecoveryPreference(store, first.apiOrigin)).toEqual(first)
    const key = [...store.values.keys()].find(value => value.startsWith('matelink.wechat.recovery.v1.') && value.includes('api.example.test'))!
    store.values.set(key, { ...first, enabled: 'yes' })
    expect(readWechatRecoveryPreference(store, first.apiOrigin)).toBeNull()
    expect(readWechatRecoveryPreference({ getStorageSync: () => { throw Error('storage unavailable') } }, first.apiOrigin)).toBeNull()
  })
})
