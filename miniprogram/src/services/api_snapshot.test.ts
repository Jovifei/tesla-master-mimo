import { beforeEach, expect, it, vi } from 'vitest'

const fake = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>() }))
vi.mock('@tarojs/taro', () => ({ default: { request: fake.request, getStorageSync: (key: string) => fake.values.get(key),
  setStorageSync: (key: string, value: unknown) => fake.values.set(key, JSON.parse(JSON.stringify(value))), removeStorageSync: (key: string) => fake.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...fake.values.keys()] }) } }))
const origin = 'https://api.example.test'
const storage = { getStorageSync: (key: string) => fake.values.get(key), setStorageSync: (key: string, value: unknown) => fake.values.set(key, JSON.parse(JSON.stringify(value))), getStorageInfoSync: () => ({ keys: [...fake.values.keys()] }) }
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
const login = (userId: string) => fake.values.set('matelink.wechat.session.v1', { apiOrigin: origin, accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
beforeEach(() => { vi.resetModules(); fake.request.mockReset(); fake.values.clear(); vi.stubEnv('TARO_APP_API_BASE_URL', origin) })
async function setup() {
  login('a')
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }))
  const [car] = await matelinkApi.getCars()
  return { matelinkApi, car }
}

it('parses actual snapshot HUD fields and reopens canonical units without storing location/geofence or refreshing report time', async () => {
  const { matelinkApi, car } = await setup()
  const stamp = '2026-10-16T01:00:00.123456789+08:00'
  fake.request.mockResolvedValueOnce(response({ car_id: 7, vehicle_uid: 'uid7', source: 'telemetry_mqtt', observed_at: stamp,
    field_sources: { Soc: 'telemetry_mqtt', Location: 'fleet_api' }, units: { unit_of_length: 'mi', unit_of_temperature: 'F' }, status: {
      state: 'driving', battery_details: { battery_level: 0, rated_battery_range: 0, ideal_battery_range: 10, est_battery_range: 20 },
      driving_details: { shift_state: ' d ', speed: 0, power: -10 }, car_status: { locked: false, windows_open: false, doors_open: null, trunk_open: true, frunk_open: false },
      climate_details: { is_climate_on: false, is_preconditioning: false, inside_temp: 32, outside_temp: -4 },
      car_geodata: { latitude: 31.12345, longitude: 121.54321, geofence: 'Private real geofence' },
      charging_details: { plugged_in: false, charging_state: 'Charging', charge_energy_added: 0, charger_power: 0, charger_voltage: 0, charger_actual_current: 0,
        time_to_full_charge: 0, charge_limit_soc: 0, charger_phases: 0, charge_current_request: 0, charge_current_request_max: 0, charge_port_door_open: false },
    } }))
  const status = await matelinkApi.getCarSnapshot(car.id, car)
  expect(fake.request.mock.calls[1][0]).toMatchObject({ url: `${origin}/api/matelink/v1/cars/7/snapshot`, header: { Authorization: 'Bearer fixture-a' } })
  expect(status).toMatchObject({ ratedRange: 0, idealRange: 16.09344, estimatedRange: 32.18688, batteryLevel: 0, speed: 0, power: -10, shiftState: 'D',
    locked: false, windowsOpen: false, doorsOpen: null, trunkOpen: true, frunkOpen: false, isClimateOn: false, isPreconditioning: false,
    insideTemp: 0, outsideTemp: -20, chargerVoltage: 0, chargerActualCurrent: 0, timeToFullChargeHours: 0, chargeLimitSoc: 0, chargerPhases: 0,
    chargeCurrentRequest: 0, chargeCurrentRequestMax: 0, chargePortDoorOpen: false, chargeEnergyAdded: 0, chargerPower: 0,
    observedAt: stamp, source: 'telemetry_mqtt', fieldSources: { Soc: 'telemetry_mqtt', Location: 'fleet_api' }, geofence: 'Private real geofence' })
  const { writeVehicleCache, readVehicleCache } = await import('./vehicle_cache')
  expect(writeVehicleCache(storage, 'a', origin, 'uid7', status)).toBe(true)
  const reopened = readVehicleCache(storage, 'a', origin, 'uid7')!
  expect(reopened).toMatchObject({ ratedRange: 0, idealRange: 16.09344, estimatedRange: 32.18688, shiftState: 'D', chargerVoltage: 0, chargerActualCurrent: 0,
    chargeLimitSoc: 0, timeToFullChargeHours: 0, isPreconditioning: false, observedAt: stamp, source: 'telemetry_mqtt', fieldSources: status.fieldSources, location: null })
  expect(reopened.geofence).toBeUndefined()
  const serialized = JSON.stringify([...fake.values].filter(([key]) => key.startsWith('matelink.status.')))
  expect(serialized).not.toMatch(/31\.12345|121\.54321|Private real geofence|latitude|longitude|geofence/)
  for (const [user, apiOrigin, uid] of [['b', origin, 'uid7'], ['a', 'https://other.test', 'uid7'], ['a', origin, 'uid8']]) expect(readVehicleCache(storage, user, apiOrigin, uid)).toBeNull()
})

it('preserves missing/invalid status fields as unknown, independent range fallback candidates and bounded source metadata', async () => {
  const { matelinkApi, car } = await setup()
  fake.request.mockResolvedValueOnce(response({ source: 'future_provider', observed_at: 'bad-but-original-time', field_sources: {
    ...Object.fromEntries(Array.from({ length: 80 }, (_, i) => [`Field${i}`, 'telemetry_mqtt'])), constructor: 'fleet_api', 'private-field': 'private-token',
  }, units: { unit_of_length: 'yards', unit_of_temperature: 'unknown' }, status: {
    battery_details: { rated_battery_range: null, ideal_battery_range: 10, est_battery_range: 20 },
    driving_details: { shift_state: 'invalid' }, climate_details: { is_preconditioning: 'false' },
    charging_details: { charger_voltage: -1, charger_actual_current: -4, time_to_full_charge: -1, charge_limit_soc: 101, charger_phases: 1.5, charge_port_door_open: 0 },
  } }))
  const status = await matelinkApi.getCarSnapshot(car.id, car)
  expect(status).toMatchObject({ ratedRange: null, idealRange: null, estimatedRange: null, shiftState: null, isPreconditioning: null,
    chargerVoltage: null, chargerActualCurrent: -4, timeToFullChargeHours: null, chargeLimitSoc: null, chargerPhases: null, chargePortDoorOpen: null,
    source: 'future_provider', observedAt: 'bad-but-original-time' })
  expect(Object.keys(status.fieldSources ?? {})).toHaveLength(64)
  expect(Object.prototype.hasOwnProperty.call(status.fieldSources, 'constructor')).toBe(false)
  expect(JSON.stringify(status.fieldSources)).not.toContain('private-token')
  fake.request.mockResolvedValueOnce(response({ units: { unit_of_length: 'km', unit_of_temperature: 'C' }, status: { battery_details: { rated_battery_range: null, ideal_battery_range: 0, est_battery_range: 99 } } }))
  const fallback = await matelinkApi.getCarSnapshot(car.id, car)
  expect(fallback.ratedRange ?? fallback.idealRange ?? fallback.estimatedRange).toBe(0)
  expect(fallback).toMatchObject({ chargerVoltage: null, chargerActualCurrent: null, timeToFullChargeHours: null, chargeLimitSoc: null, observedAt: null, source: null })
})

it.each([404, 501])('uses the existing same-origin status contract only after explicit snapshot HTTP %s', async statusCode => {
  const { matelinkApi, car } = await setup()
  fake.request.mockResolvedValueOnce({ statusCode, data: { error: 'not_supported' }, header: {} }).mockResolvedValueOnce(response({ status: { charging_details: { charger_voltage: 230, charger_actual_current: 16, time_to_full_charge: 1.5, charge_limit_soc: 80 } } }))
  await expect(matelinkApi.getCarSnapshot(car.id, car)).resolves.toMatchObject({ chargerVoltage: 230, chargerActualCurrent: 16, timeToFullChargeHours: 1.5, chargeLimitSoc: 80, source: null, observedAt: null })
  expect(fake.request.mock.calls[2][0].url).toBe(`${origin}/api/v1/cars/7/status`)
})

it.each([401, 429, 500, 503])('does not hide HTTP %s behind a legacy status fallback', async statusCode => {
  const { matelinkApi, car } = await setup()
  fake.request.mockResolvedValueOnce({ statusCode, data: { error: 'provider_unavailable' }, header: {} })
  await expect(matelinkApi.getCarSnapshot(car.id, car)).rejects.toMatchObject({ status: statusCode })
  expect(fake.request).toHaveBeenCalledTimes(2)
})

it('preserves terminal refresh HTTP401 classification and never falls back after the owning session expires', async () => {
  const { matelinkApi, car } = await setup()
  const stored = fake.values.get('matelink.wechat.session.v1') as Record<string, unknown>
  fake.values.set('matelink.wechat.session.v1', { ...stored, refreshToken: 'fixture-refresh' })
  fake.request.mockResolvedValueOnce({ statusCode: 401, data: { error: 'expired' }, header: {} })
    .mockResolvedValueOnce({ statusCode: 401, data: { error: 'session_required' }, header: {} })
  await expect(matelinkApi.getCarSnapshot(7, car)).rejects.toMatchObject({ status: 401, code: 'session_required' })
  expect(fake.request.mock.calls.map(([request]) => request.url)).toEqual([`${origin}/api/v1/cars`, `${origin}/api/matelink/v1/cars/7/snapshot`, `${origin}/v1/session/refresh`])
})

it('rejects offline car authority, foreign UID, malformed snapshot, and late old-account delivery', async () => {
  const { matelinkApi, car } = await setup()
  await expect(matelinkApi.getCarSnapshot(7, { ...car, remoteVerified: false })).rejects.toMatchObject({ code: 'vehicle_not_found' })
  fake.request.mockResolvedValueOnce(response({ vehicle_uid: 'foreign', status: {} }))
  await expect(matelinkApi.getCarSnapshot(7, car)).rejects.toMatchObject({ code: 'vehicle_not_found' })
  fake.request.mockResolvedValueOnce(response({ source: 'fleet_api' }))
  await expect(matelinkApi.getCarSnapshot(7, car)).rejects.toMatchObject({ code: 'invalid_api_response' })
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const pending = matelinkApi.getCarSnapshot(7, car); login('b'); release(response({ source: 'fleet_api', status: {} }))
  await expect(pending).rejects.toMatchObject({ code: 'session_changed' })
})

it('does not issue a legacy request after network failure or a superseded unsupported response', async () => {
  const { matelinkApi, car } = await setup()
  fake.request.mockRejectedValueOnce(new Error('synthetic offline'))
  await expect(matelinkApi.getCarSnapshot(7, car)).rejects.toMatchObject({ code: 'network_error' })
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const pending = matelinkApi.getCarSnapshot(7, car); login('b'); release({ statusCode: 404, data: {}, header: {} })
  await expect(pending).rejects.toMatchObject({ code: 'session_changed' })
  expect(fake.request).toHaveBeenCalledTimes(3)
})
