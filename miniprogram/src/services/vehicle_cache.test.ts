import { describe, expect, it } from 'vitest'
import { clearVehicleCachesForAccount, readVehicleCache, writeVehicleCache } from './vehicle_cache'
import type { CarStatus } from './types'

function store() {
  const data = new Map<string, unknown>()
  return { data, getStorageSync: (k: string) => data.get(k), setStorageSync: (k: string, v: unknown) => { data.set(k, v) },
    getStorageInfoSync: () => ({ keys: [...data.keys()] }), removeStorageSync: (k: string) => { data.delete(k) } }
}
const origin = 'https://api.example.test'
const sample = { batteryLevel: 0, ratedRange: 0, speed: 0, power: -10, location: { latitude: 0, longitude: 0 }, locked: false,
  source: 'fleet_telemetry_native', observedAt: '2026-10-14T00:00:00Z' } as CarStatus
describe('scoped vehicle snapshot cache', () => {
  it('reopens observed zero, negative power and false without inventing live state', () => {
    const s = store(); expect(writeVehicleCache(s, 'owner', origin, 'uid-A', sample)).toBe(true)
    const value = readVehicleCache(s, 'owner', origin, 'uid-A')!
    expect([value.batteryLevel, value.speed, value.power, value.locked]).toEqual([0, 0, -10, false])
    expect(value.location).toBeNull(); expect(value.source).toBe(sample.source)
    expect(JSON.stringify([...s.data.values()])).not.toContain('latitude')
  })
  it('isolates origin, account and stable UID', () => {
    const s = store(); writeVehicleCache(s, 'owner', origin, 'uid-A', sample)
    expect(readVehicleCache(s, 'other', origin, 'uid-A')).toBeNull()
    expect(readVehicleCache(s, 'owner', 'https://other.example.test', 'uid-A')).toBeNull()
    expect(readVehicleCache(s, 'owner', origin, 'uid-B')).toBeNull()
  })
  it('rejects invalid cache numbers and strips extra credential fields', () => {
    const s = store(); writeVehicleCache(s, 'owner', origin, 'uid-A', { ...sample, batteryLevel: 101, speed: Number.NaN, access_token: 'not-for-cache' } as CarStatus)
    const v = readVehicleCache(s, 'owner', origin, 'uid-A')!
    expect(v.batteryLevel).toBeNull(); expect(v.speed).toBeNull()
    expect(JSON.stringify([...s.data.values()])).not.toContain('not-for-cache')
  })
  it('keeps other accounts including dotted IDs when clearing one account', () => {
    const s = store(); for (const u of ['a', 'a.b']) writeVehicleCache(s, u, origin, 'uid', sample)
    clearVehicleCachesForAccount(s, 'a', origin)
    expect(readVehicleCache(s, 'a', origin, 'uid')).toBeNull(); expect(readVehicleCache(s, 'a.b', origin, 'uid')).not.toBeNull()
  })
  it('treats quota failure as optional and never grants numeric-ID authority', () => {
    expect(writeVehicleCache({ setStorageSync: () => { throw Error('quota') } }, 'owner', origin, 'uid', sample)).toBe(false)
    expect(readVehicleCache(store(), '', origin, 'uid')).toBeNull()
    expect(readVehicleCache(store(), 'owner', origin, 'uid')).toBeNull()
  })
})
