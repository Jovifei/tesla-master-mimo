import { describe, expect, it } from 'vitest'
import {
  clearAppSession,
  clearPendingTeslaAuthorization,
  readAppSession,
  readPendingWechatLink,
  readPendingTeslaAuthorization,
  writeAppSession,
  writePendingWechatLink,
  writePendingTeslaAuthorization,
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

describe('session storage', () => {
  it('round trips rotating tokens and account/link state', () => {
    const store = storage()
    writeAppSession(store, {
      accessToken: 'access',
      refreshToken: 'refresh',
      expiresAt: '2026-09-13T00:00:00Z',
      userId: 'user-a',
      linkRequired: false,
    })
    expect(readAppSession(store)).toEqual({
      accessToken: 'access',
      refreshToken: 'refresh',
      expiresAt: '2026-09-13T00:00:00Z',
      userId: 'user-a',
      linkRequired: false,
    })
  })

  it('stores the short lived link token separately and clears it on logout', () => {
    const store = storage()
    writePendingWechatLink(store, { linkToken: 'link-token', expiresAt: null })
    expect(readPendingWechatLink(store)).toEqual({ linkToken: 'link-token', expiresAt: null })
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
})
