import { expect, it } from 'vitest'
import { readTpmsProfile, tpmsProfileKey, writeTpmsProfile } from './tpms_profile'
import type { TpmsProfile } from '../domain/tpms_alerts'
const scope = { origin: 'https://api.example.test', userId: 'a.b', stableId: 'uid' }
const profile: TpmsProfile = { enabled: true, targetBar: 2.9, lowBar: 2.6, highBar: 3.4, changeEnabled: true, deltaBar: .3, windowHours: 24 }
function storage() { const values = new Map<string, unknown>(); return { values, getStorageSync: (key: string) => values.get(key), setStorageSync: (key: string, value: unknown) => values.set(key, JSON.parse(JSON.stringify(value))), getStorageInfoSync: () => ({ keys: [...values.keys()] }) } }
it('reopens only exact origin account UID and strips extra input fields', () => {
  const store = storage()
  expect(writeTpmsProfile(store, scope, { ...profile, token: 'private' } as TpmsProfile, () => true).written).toBe(true)
  expect(readTpmsProfile(store, scope)).toEqual(profile)
  for (const other of [{ userId: 'a' }, { stableId: 'other' }, { origin: 'https://other.test' }]) expect(readTpmsProfile(store, { ...scope, ...other })).toBeNull()
  expect(JSON.stringify([...store.values])).not.toContain('private')
})
it('preserves corrupt archive and rejects stale save both before and after budget inspection', () => {
  const store = storage(), key = tpmsProfileKey(scope)!, corrupt = { schema: 99, profile }
  store.values.set(key, corrupt)
  expect(writeTpmsProfile(store, scope, profile, () => true).reason).toBe('ambiguous_archive')
  expect(store.values.get(key)).toBe(corrupt)
  store.values.clear()
  let checks = 0
  expect(writeTpmsProfile(store, scope, profile, () => ++checks === 1).reason).toBe('scope_changed')
  expect(store.values.size).toBe(0)
})
it('uses the shared budget and never evicts another scope', () => {
  const store = storage(); store.values.set('matelink.history.owner', 'x'.repeat(800 * 1024))
  expect(writeTpmsProfile(store, scope, profile, () => true).reason).toBe('cache_budget')
  expect(store.values.size).toBe(1)
})
