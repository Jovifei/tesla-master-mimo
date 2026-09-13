import { describe, expect, it } from 'vitest'
import {
  clearAppSession,
  readAppSession,
  readPendingWechatLink,
  writeAppSession,
  writePendingWechatLink,
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

  it('rejects malformed sessions without an access token', () => {
    const store = storage()
    store.setStorageSync('matelink.wechat.session.v1', { refreshToken: 'refresh' })
    expect(readAppSession(store)).toBeNull()
  })
})
