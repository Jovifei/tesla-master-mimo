import { beforeEach, expect, it, vi } from 'vitest'

const fake = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>() }))
vi.mock('@tarojs/taro', () => ({ default: { request: fake.request, getStorageSync: (key: string) => fake.values.get(key),
  setStorageSync: (key: string, value: unknown) => fake.values.set(key, JSON.parse(JSON.stringify(value))), removeStorageSync: (key: string) => fake.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...fake.values.keys()] }) } }))
const origin = 'https://api.example.test'
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

it('projects the extended Fleet display fields from the status payload', async () => {
  const { matelinkApi, car } = await setup()
  fake.request.mockResolvedValueOnce(response({ status: {
    car_status: { locked: true, valet_mode: true,
      speed_limit_mode: { active: true, current_limit_kmh: 113, pin_code_required: false },
      media_info: { media_state: 'Playing', title: 'Song', artist: 'Singer', album: 'Album' } },
    car_versions: { version: '2025.44.1', software_update: { status: 'downloading', download_percent: 42, install_percent: 0, expected_duration_sec: 1500 } },
    climate_details: { is_climate_on: true, driver_temp_setting: 22.5, passenger_temp_setting: 21, fan_status: 3,
      is_front_defroster_on: true, is_rear_defroster_on: false, seat_heater_left: 2, seat_heater_right: 0,
      seat_heater_rear_left: 1, seat_heater_rear_center: 0, seat_heater_rear_right: 3, steering_wheel_heater: true,
      defrost_mode: 1, climate_keeper_mode: 'Keep', cabin_overheat_protection: 'On', cabin_overheat_protection_actively_cooling: true, side_mirror_heaters: false },
    charging_details: { plugged_in: true, conn_charge_cable: 'IEC', fast_charger_brand: 'Tesla', fast_charger_type: 'Supercharger',
      charge_port_latch: 'Engaged', battery_heater_on: true, scheduled_charging_start_time: '2026-11-05T23:00:00Z',
      scheduled_departure_time: '2026-11-06T07:30:00Z', minutes_to_full_charge: 95, charge_range_added_rated: 12.5, charge_range_added_ideal: 14 },
    command_authorization: { ready: true, missing_scopes: [] },
  } }))
  const status = await matelinkApi.getCarStatus(car.id, car)
  expect(status).toMatchObject({
    valetMode: true,
    speedLimit: { active: true, currentLimitKmh: 113, pinCodeRequired: false },
    media: { state: 'Playing', title: 'Song', artist: 'Singer', album: 'Album' },
    softwareVersion: '2025.44.1',
    softwareUpdate: { status: 'downloading', downloadPercent: 42, installPercent: 0, expectedDurationSec: 1500 },
    driverTempSetting: 22.5, passengerTempSetting: 21, fanStatus: 3,
    frontDefrosterOn: true, rearDefrosterOn: false, seatHeaterLeft: 2, seatHeaterRearRight: 3,
    steeringWheelHeater: true, defrostMode: 1, climateKeeperMode: 'Keep',
    cabinOverheatProtection: 'On', cabinOverheatProtectionCooling: true, sideMirrorHeaters: false,
    chargeCable: 'IEC', fastChargerBrand: 'Tesla', fastChargerType: 'Supercharger', chargePortLatch: 'Engaged',
    batteryHeaterOn: true, scheduledChargingStartTime: '2026-11-05T23:00:00Z', scheduledDepartureTime: '2026-11-06T07:30:00Z',
    minutesToFullCharge: 95, chargeRangeAddedRated: 12.5, chargeRangeAddedIdeal: 14,
    commandAuthorization: { ready: true, missingScopes: [] },
  })
})

it('keeps absent extended blocks absent and sanitizes malformed values', async () => {
  const { matelinkApi, car } = await setup()
  fake.request.mockResolvedValueOnce(response({ status: {
    climate_details: { seat_heater_left: 9, fan_status: 2.5 },
    charging_details: { minutes_to_full_charge: -5 },
    command_authorization: { ready: false, missing_scopes: ['vehicle_cmds', 'Bad Scope!', 42, 'vehicle_charging_cmds'] },
  } }))
  const status = await matelinkApi.getCarStatus(car.id, car)
  expect(status.speedLimit).toBeUndefined()
  expect(status.media).toBeUndefined()
  expect(status.softwareUpdate).toBeUndefined()
  // Out-of-range values are treated as "not reported": the key stays absent.
  expect(status.seatHeaterLeft).toBeUndefined()
  expect(status.fanStatus).toBeUndefined()
  expect(status.minutesToFullCharge).toBeUndefined()
  expect(status.commandAuthorization).toEqual({ ready: false, missingScopes: ['vehicle_cmds', 'vehicle_charging_cmds'] })

  fake.request.mockResolvedValueOnce(response({ status: {} }))
  const bare = await matelinkApi.getCarStatus(car.id, car)
  expect(bare.commandAuthorization).toBeUndefined()
  expect(bare.valetMode).toBeUndefined()
})

it('executes a vehicle command and surfaces backend error classes as ApiError codes', async () => {
  const { matelinkApi, car } = await setup()
  fake.request.mockResolvedValueOnce(response({ command: 'unlock', result: 'unverified', verified: false }))
  const outcome = await matelinkApi.executeVehicleCommand(car.id, 'unlock', { confirm: true }, car)
  expect(outcome).toEqual({ command: 'unlock', result: 'unverified', verified: false })
  const sent = fake.request.mock.calls[1][0]
  expect(sent.url).toBe(`${origin}/api/v1/cars/7/commands/unlock`)
  expect(sent.method).toBe('POST')
  expect(sent.data).toEqual({ confirm: true })

  fake.request.mockResolvedValueOnce({ statusCode: 403, data: { error: 'scope_missing' }, header: {} })
  await expect(matelinkApi.executeVehicleCommand(car.id, 'lock', {}, car))
    .rejects.toMatchObject({ code: 'scope_missing', status: 403 })

  fake.request.mockResolvedValueOnce({ statusCode: 409, data: { error: 'vehicle_moving' }, header: {} })
  await expect(matelinkApi.executeVehicleCommand(car.id, 'windows_close', {}, car))
    .rejects.toMatchObject({ code: 'vehicle_moving', status: 409 })
})

it('parses nearby charging sites and vehicle places payloads', async () => {
  const { matelinkApi, car } = await setup()
  fake.request.mockResolvedValueOnce(response({ sites: [
    { name: 'Near SC', type: 'supercharger', latitude: 30.1, longitude: 120.1, distance_km: 3.22, available_stalls: 5, total_stalls: 6, site_closed: false },
    { name: 'Hotel', type: 'destination', latitude: 30.2, longitude: 120.15, distance_km: 8.05, available_stalls: null, total_stalls: null, site_closed: null },
  ], meta: { source: 'fleet_api' } }))
  const nearby = await matelinkApi.getNearbyChargingSites(car.id, car)
  expect(nearby.source).toBe('fleet_api')
  expect(nearby.sites).toHaveLength(2)
  expect(nearby.sites[0]).toMatchObject({ name: 'Near SC', distanceKm: 3.22, availableStalls: 5, totalStalls: 6, siteClosed: false })
  expect(nearby.sites[1].availableStalls).toBeNull()

  fake.request.mockResolvedValueOnce(response({ places: [
    { id: 1, name: '家', latitude: 30.2741, longitude: 120.1551, radius_m: 200 },
    { id: 'bad', name: 'broken' },
  ] }))
  const places = await matelinkApi.getVehiclePlaces(car.id, car)
  expect(places).toEqual([{ id: 1, name: '家', latitude: 30.2741, longitude: 120.1551, radiusM: 200 }])

  fake.request.mockResolvedValueOnce(response({ id: 2, name: '公司', latitude: 30.3, longitude: 120.2, radius_m: 350 }))
  const created = await matelinkApi.createVehiclePlace(car.id, { name: '公司', latitude: 30.3, longitude: 120.2, radiusM: 350 }, car)
  expect(created).toMatchObject({ id: 2, name: '公司', radiusM: 350 })
  const calls = fake.request.mock.calls
  expect(calls[calls.length - 1]?.[0].data).toEqual({ name: '公司', latitude: 30.3, longitude: 120.2, radius_m: 350 })

  fake.request.mockResolvedValueOnce(response({ deleted: true }))
  await expect(matelinkApi.deleteVehiclePlace(car.id, 2, car)).resolves.toBe(true)
})
