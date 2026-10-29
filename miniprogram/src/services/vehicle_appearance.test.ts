import { expect, it } from 'vitest'
import { clearVehicleAppearance, DEFAULT_VEHICLE_APPEARANCE, readVehicleAppearance, validVehicleAppearance, vehicleAppearanceKey, writeVehicleAppearance } from './vehicle_appearance'
import type { VehicleVisualConfig } from '../domain/vehicle_visual'

const scope = { origin: 'https://api.example.test', userId: 'account.a', stableId: 'uid.one' }
const preview = { origin: scope.origin, userId: null, stableId: null }
const config = { model: 'Y', variant: 'myj', exteriorColor: 'PX02', wheelType: 'WY19P', modelYear: 2024 }
function storage() {
  const values = new Map<string, unknown>()
  return { values, getStorageSync: (key: string) => values.get(key), setStorageSync: (key: string, value: unknown) => values.set(key, JSON.parse(JSON.stringify(value))),
    getStorageInfoSync: () => ({ keys: [...values.keys()] }), removeStorageSync: (key: string) => values.delete(key) }
}

it('uses an explicit default without inventing or persisting an account override', () => {
  const store = storage()
  expect(DEFAULT_VEHICLE_APPEARANCE).toEqual({ model: 'Y', variant: 'myj', exteriorColor: 'PX02', wheelType: 'WY19P' })
  expect(readVehicleAppearance(store, scope)).toBeNull()
  expect(readVehicleAppearance(store, preview)).toBeNull()
  expect(store.values.size).toBe(0)
})

it('round trips only visual fields and keeps modelYear independent of explicit variant', () => {
  const store = storage(), input = { ...config, car_id: 99, stableId: 'other', vin: 'PRIVATE', access_token: 'PRIVATE', location: { latitude: 1 } }
  expect(writeVehicleAppearance(store, scope, input)).toBe(true)
  expect(readVehicleAppearance(store, scope)).toEqual(config)
  expect(input.car_id).toBe(99)
  expect(JSON.stringify([...store.values])).not.toMatch(/PRIVATE|latitude|car_id|access_token/)
  expect(readVehicleAppearance(store, scope)?.variant).toBe('myj')
  const copy = readVehicleAppearance(store, scope)!; copy.modelYear = 2099
  expect(readVehicleAppearance(store, scope)?.modelYear).toBe(2024)
})

it('keeps preview separate and never adopts it into a login vehicle scope', () => {
  const store = storage()
  expect(writeVehicleAppearance(store, preview, config)).toBe(true)
  expect(readVehicleAppearance(store, scope)).toBeNull()
  expect(writeVehicleAppearance(store, scope, { model: '3', variant: 'm3', exteriorColor: 'PPSW', wheelType: 'W38B' })).toBe(true)
  expect(readVehicleAppearance(store, preview)?.model).toBe('Y')
  expect(clearVehicleAppearance(store, scope)).toBe(true)
  expect(readVehicleAppearance(store, scope)).toBeNull()
  expect(readVehicleAppearance(store, preview)?.model).toBe('Y')
})

it('isolates origin account UID and prevents literal-dot component collisions', () => {
  const store = storage()
  expect(writeVehicleAppearance(store, scope, config)).toBe(true)
  for (const other of [{ ...scope, userId: 'account' }, { ...scope, stableId: 'uid' }, { ...scope, origin: 'https://other.test' }]) expect(readVehicleAppearance(store, other)).toBeNull()
  const a = { ...scope, userId: 'a.b', stableId: 'c' }, b = { ...scope, userId: 'a', stableId: 'b.c' }
  expect(vehicleAppearanceKey(a)).not.toBe(vehicleAppearanceKey(b))
  writeVehicleAppearance(store, a, config)
  expect(clearVehicleAppearance(store, b)).toBe(true)
  expect(readVehicleAppearance(store, a)).toEqual(config)
})

it.each([{ ...scope, userId: null }, { ...scope, stableId: null }, { ...scope, origin: 'http://api.example.test' }, { ...scope, origin: 'https://api.example.test/path' }, { ...scope, userId: '' }])('rejects ambiguous or invalid scope %j', invalid => {
  const store = storage()
  expect(writeVehicleAppearance(store, invalid, config)).toBe(false)
  expect(readVehicleAppearance(store, invalid)).toBeNull()
  expect(clearVehicleAppearance(store, invalid)).toBe(false)
  expect(store.values.size).toBe(0)
})

it('validates supported explicit variants/assets and years, without inferring generation from a year', () => {
  for (const modelYear of [2008, 2100]) expect(validVehicleAppearance({ ...config, modelYear })).toBe(true)
  for (const invalid of [{ ...config, modelYear: 2007 }, { ...config, modelYear: 2101 }, { ...config, modelYear: 2025.5 },
    { ...config, variant: null }, { ...config, model: '3' }, { ...config, variant: 'future' }, { ...config, exteriorColor: 'unknown' },
    { ...config, wheelType: 'invented' }]) expect(validVehicleAppearance(invalid)).toBe(false)
  expect(validVehicleAppearance({ ...config, modelYear: 2024 })).toBe(true)
})

it('preserves unknown/corrupt schemas on write and reset', () => {
  const store = storage(), key = vehicleAppearanceKey(scope)!, corrupt = { schema: 2, scope: key, source: 'user_configured', config }
  store.values.set(key, corrupt)
  expect(readVehicleAppearance(store, scope)).toBeNull()
  expect(writeVehicleAppearance(store, scope, config)).toBe(false)
  expect(clearVehicleAppearance(store, scope)).toBe(false)
  expect(store.values.get(key)).toBe(corrupt)
})

it('refuses quota failures and shared-budget excess without evicting old data', () => {
  const store = storage()
  writeVehicleAppearance(store, scope, config)
  const before = JSON.stringify([...store.values])
  const fail = { ...store, setStorageSync: () => { throw new Error('synthetic quota') } }
  expect(writeVehicleAppearance(fail, scope, { ...config, modelYear: 2026 })).toBe(false)
  expect(JSON.stringify([...store.values])).toBe(before)
  store.values.set('matelink.owner.archive', 'x'.repeat(800 * 1024))
  expect(writeVehicleAppearance(store, scope, config)).toBe(false)
  expect(store.values.has('matelink.owner.archive')).toBe(true)
})

it('does not write an invalid visual config', () => {
  const store = storage()
  expect(writeVehicleAppearance(store, scope, { ...config, modelYear: '2025' } as unknown as VehicleVisualConfig)).toBe(false)
  expect(store.values.size).toBe(0)
})
