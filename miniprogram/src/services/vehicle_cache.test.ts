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
  it('revalidates new HUD fields and old private snapshot extras without rewriting original evidence on read', () => {
    const s = store(); writeVehicleCache(s, 'owner', origin, 'uid-A', sample)
    const key = [...s.data.keys()][0]
    const old = { schema: 1, scope: key, savedAt: '2099-01-01T00:00:00Z', status: { ...sample,
      idealRange: 0, estimatedRange: 100, chargerVoltage: -1, chargerActualCurrent: -4, timeToFullChargeHours: 0, chargeLimitSoc: 50.5,
      chargerPhases: 1.5, chargeCurrentRequest: NaN, chargeCurrentRequestMax: 0, chargePortDoorOpen: false, isPreconditioning: false,
      shiftState: 'not-real', geofence: 'private old geofence', cachedAddress: 'private address', fieldSources: { Soc: 'telemetry_mqtt', constructor: 'private-token' } } }
    s.data.set(key, old)
    const value = readVehicleCache(s, 'owner', origin, 'uid-A')!
    expect(value).toMatchObject({ idealRange: 0, estimatedRange: 100, chargerVoltage: null, chargerActualCurrent: -4, timeToFullChargeHours: 0,
      chargeLimitSoc: null, chargerPhases: null, chargeCurrentRequest: null, chargeCurrentRequestMax: 0, chargePortDoorOpen: false,
      isPreconditioning: false, shiftState: null, observedAt: sample.observedAt, source: sample.source, fieldSources: { Soc: 'telemetry_mqtt' }, location: null })
    expect(JSON.stringify(value)).not.toMatch(/private|geofence|cachedAddress|latitude|longitude/)
    expect(s.data.get(key)).toBe(old)
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
  it('preserves old literal-dot ambiguous snapshot suffixes when clearing exact encoded UID', () => {
    const s = store(), encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
    const ambiguous = `matelink.status.v1.${encode(origin)}.a.b.uid`
    s.data.set(ambiguous, { schema: 1, scope: ambiguous, status: sample })
    writeVehicleCache(s, 'a', origin, 'b.uid', sample)
    clearVehicleCachesForAccount(s, 'a', origin)
    expect(s.data.has(ambiguous)).toBe(true)
    expect(readVehicleCache(s, 'a', origin, 'b.uid')).toBeNull()
  })
  it('keeps unknown snapshot schemas and malformed keys without blocking later known cleanup', () => {
    const s = store(), encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E'), prefix = `matelink.status.v1.${encode(origin)}.a.`
    s.data.set(prefix + '%ZZ', { schema: 1, scope: prefix + '%ZZ' })
    s.data.set(prefix + 'future', { schema: 2, scope: prefix + 'future' })
    writeVehicleCache(s, 'a', origin, 'uid', sample)
    clearVehicleCachesForAccount(s, 'a', origin)
    expect(s.data.has(prefix + '%ZZ')).toBe(true); expect(s.data.has(prefix + 'future')).toBe(true)
    expect(readVehicleCache(s, 'a', origin, 'uid')).toBeNull()
  })
  it('treats quota failure as optional and never grants numeric-ID authority', () => {
    expect(writeVehicleCache({ setStorageSync: () => { throw Error('quota') } }, 'owner', origin, 'uid', sample)).toBe(false)
    expect(readVehicleCache(store(), '', origin, 'uid')).toBeNull()
    expect(readVehicleCache(store(), 'owner', origin, 'uid')).toBeNull()
  })
})
