import { beforeEach, expect, it, vi } from 'vitest'
import type { Car } from './types'
const platform = vi.hoisted(() => ({ values: new Map<string, unknown>(), request: vi.fn() }))
vi.mock('@tarojs/taro', () => ({ default: { request: platform.request,
  getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, value),
  removeStorageSync: (key: string) => platform.values.delete(key), getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) } }))
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
const session = (userId: string) => ({ apiOrigin: 'https://api.example.test', accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
beforeEach(() => { vi.resetModules(); platform.values.clear(); platform.request.mockReset(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test') })
async function setup() {
  platform.values.set('matelink.wechat.session.v1', session('a'))
  const { matelinkApi } = await import('./api'), { analysisApi } = await import('./analysis_api')
  platform.request.mockResolvedValueOnce(response({ cars: [{ car_id: 7, vehicle_uid: 'uid-a', model: 'Y' }] }))
  const [car] = await matelinkApi.getCars()
  return { car, analysisApi, matelinkApi }
}

it('reads only the actual battery endpoint and retains cloud unsupported data as unknown', async () => {
  const { car, analysisApi } = await setup()
  platform.request.mockResolvedValueOnce(response({ battery_health: null, meta: { availability: 'unsupported', source: 'local_history', quality_reason: 'no_battery_health' } }))
  await expect(analysisApi.getBatteryHealth(car)).resolves.toMatchObject({ batteryHealth: null, meta: { availability: 'unsupported', source: 'local_history', qualityReason: 'no_battery_health' } })
  expect(platform.request.mock.calls[1][0]).toMatchObject({ url: 'https://api.example.test/api/v1/cars/7/battery-health', method: 'GET', header: { Authorization: 'Bearer fixture-a' } })
  platform.request.mockResolvedValueOnce(response({ battery_health: { max_range: 300, current_range: 250, current_capacity: 0, rated_efficiency: 200, battery_health_percentage: 101 }, units: { unit_of_length: 'mi' } }))
  const result = await analysisApi.getBatteryHealth(car)
  expect(result.batteryHealth?.maxRangeKm).toBeCloseTo(482.8032)
  expect(result.batteryHealth?.ratedEfficiencyWhKm).toBeCloseTo(200 / 1.609344)
  expect(result.batteryHealth?.currentCapacityKwh).toBeNull()
  expect(result.batteryHealth?.healthPercentage).toBe(101)
  expect(result.meta.source).toBeNull()
})

it('rejects cached numeric car authority, forged response identity and malformed updates', async () => {
  const { car, analysisApi } = await setup()
  await expect(analysisApi.getUpdates({ ...car, remoteVerified: false })).rejects.toMatchObject({ code: 'vehicle_not_found' })
  expect(platform.request).toHaveBeenCalledTimes(1)
  platform.request.mockResolvedValueOnce(response({ car_id: 8, updates: [] }))
  await expect(analysisApi.getUpdates(car)).rejects.toMatchObject({ code: 'vehicle_not_found' })
  platform.request.mockResolvedValueOnce(response({ updates: {} }))
  await expect(analysisApi.getUpdates(car)).rejects.toMatchObject({ code: 'invalid_api_response' })
  platform.request.mockResolvedValueOnce(response({ updates: [{ update_id: 1, version: '2026.38.1', start_date: '2026-10-15T00:00:00Z' }] }))
  await expect(analysisApi.getUpdates(car)).resolves.toMatchObject({ items: [{ id: '1', version: '2026.38.1' }] })
  expect(platform.request.mock.calls[3][0].url).toBe('https://api.example.test/api/v1/cars/7/updates?page=1&show=20')
})
it('preserves outer pagination when the data body has separate availability metadata', async () => {
  const { car, analysisApi } = await setup()
  platform.request.mockResolvedValueOnce({ statusCode: 200, data: { data: { updates: [{ update_id: 1, version: 'v1' }], meta: { availability: 'available' } }, meta: { page: 1, total_pages: 2 } }, header: {} })
  await expect(analysisApi.getUpdates(car)).resolves.toMatchObject({ hasMore: true, meta: { availability: 'available' } })
})

it('caps real history at 25 pages/500 rows and never claims a complete interval', async () => {
  const { car, analysisApi } = await setup()
  platform.request.mockImplementation(options => {
    const page = Number(/page=(\d+)/.exec(options.url)?.[1])
    return Promise.resolve(response({ drives: Array.from({ length: 20 }, (_, index) => ({ drive_id: (page - 1) * 20 + index + 1, source: 'teslamate_archive', start_date: `2026-10-14T00:00:00Z` })),
      meta: { page, show: 20, total: 1000, total_pages: 50, availability: 'available', source: 'teslamate_archive' } }))
  })
  const result = await analysisApi.getHistory(car, 'drives')
  expect(result).toMatchObject({ complete: false, pages: 25, receivedPages: 25, reason: 'limit' })
  expect(result.items).toHaveLength(500)
  expect(platform.request).toHaveBeenCalledTimes(26)
})

it('page cancellation and late account change reject actual responses without another fetch', async () => {
  const { car, analysisApi } = await setup()
  let current = true
  platform.request.mockImplementationOnce(async () => { current = false; return response({ drives: [], meta: { total: 0 } }) })
  await expect(analysisApi.getHistory(car, 'drives', { isCurrent: () => current })).rejects.toMatchObject({ code: 'session_changed' })
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const pending = analysisApi.getUpdates(car)
  platform.values.set('matelink.wechat.session.v1', session('b'))
  release(response({ updates: [{ update_id: 9, version: 'old-account' }] }))
  await expect(pending).rejects.toMatchObject({ code: 'session_changed' })
  expect(platform.request).toHaveBeenCalledTimes(3)
})

it('normalizes actual online detail samples with response units and never writes private points', async () => {
  const { car, analysisApi } = await setup()
  const before = JSON.stringify([...platform.values])
  platform.request.mockResolvedValueOnce(response({ units: { unit_of_length: 'mi' }, drive: { drive_id: 9, source: 'teslamate_archive', quality_state: 'observed',
    start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T00:10:00Z', odometer_details: { odometer_distance: 10 }, energy_consumed_net: -0.5,
    drive_details: [
      { date: '2026-10-14T00:01:00Z', speed: 10, power: -2, battery_level: 0, elevation: 0, latitude: 31.123, longitude: 121.123 },
      { date: 'invalid', speed: 20 }, { date: '2026-10-14T00:11:00Z', speed: 99 },
    ] } }))
  const result = await analysisApi.getDriveEvidence(car, '9')
  expect(result.drive?.distanceKm).toBeCloseTo(16.09344)
  expect(result.points).toHaveLength(1)
  expect(result.points[0]).toMatchObject({ speedKmh: 16.09344, powerKw: -2, batteryLevel: 0, elevationM: 0 })
  expect(result.pointsComplete).toBe(false)
  expect(JSON.stringify([...platform.values])).toBe(before)
})

it('quarantined samples and explicit unknown speed units cannot become an observed curve', async () => {
  const { car, analysisApi } = await setup()
  const drive = { drive_id: 9, source: 'telemetry_mqtt', start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T00:10:00Z',
    drive_details: [{ date: '2026-10-14T00:01:00Z', speed: 10, power: 0 }] }
  platform.request.mockResolvedValueOnce(response({ units: { unit_of_length: 'km' }, drive: { ...drive, quality_state: 'quarantined' } }))
  await expect(analysisApi.getDriveEvidence(car, '9')).resolves.toMatchObject({ points: [], reason: 'quarantined_history' })
  platform.request.mockResolvedValueOnce(response({ units: { unit_of_length: 'km' }, drive: { ...drive, speed_unit: 'unsupported' } }))
  const result = await analysisApi.getDriveEvidence(car, '9')
  expect(result.points[0]).toMatchObject({ speedKmh: null, powerKw: 0 })
  platform.request.mockResolvedValueOnce(response({}))
  await expect(analysisApi.getDriveEvidence(car, '9')).rejects.toMatchObject({ code: 'invalid_api_response' })
  platform.request.mockResolvedValueOnce(response({}))
  await expect(analysisApi.getParkedDetail(car, '1', '2')).rejects.toMatchObject({ code: 'invalid_api_response' })
})

it('ignores unqualified parked scalar energy, admits exact no-activity endpoints and retains valid cloud-unavailable response', async () => {
  const { car, analysisApi } = await setup()
  const parked = { older_drive_id: 1, newer_drive_id: 2, start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T01:00:00Z', source: 'telemetry_mqtt', energy_kwh: 99, start_battery_level: 80, end_battery_level: 78, battery_delta: 2 }
  platform.request.mockResolvedValueOnce(response(parked))
  await expect(analysisApi.getParkedDetail(car, '1', '2')).resolves.toMatchObject({ detail: { energyKwh: null, averagePowerW: null, batteryDelta: 2 } })
  const metric = { value_kwh: -0.1, source: 'telemetry_mqtt', source_field: 'EnergyRemaining', unit: 'kWh', quality: 'estimated', method: 'energy_remaining_delta', measurement_point: 'nominal_battery_remaining',
    coverage_kind: 'endpoints', coverage_ratio: 1, start_date: parked.start_date, end_date: parked.end_date, observed_start_at: parked.start_date, observed_end_at: parked.end_date, activity_evidence: 'no_driving_or_charging_during_window' }
  platform.request.mockResolvedValueOnce(response({ ...parked, energy_contract: { version: 1, stored_change: metric } }))
  await expect(analysisApi.getParkedDetail(car, '1', '2')).resolves.toMatchObject({ detail: { energyKwh: -0.1, averagePowerW: -100 } })
  platform.request.mockResolvedValueOnce({ statusCode: 200, data: { data: null, error: 'parked_history_bounds_unavailable' }, header: {} })
  await expect(analysisApi.getParkedDetail(car, '1', '2')).resolves.toMatchObject({ detail: null, meta: { availability: 'unavailable', qualityReason: 'parked_history_bounds_unavailable' } })
})
it('preserves the server evidence of whether overlapping charge sessions are complete', async () => {
  const { car, analysisApi } = await setup()
  const parked = { older_drive_id: 1, newer_drive_id: 2, start_date: '2026-10-14T00:10:00Z', end_date: '2026-10-14T02:00:00Z', source: 'teslamate_archive', charge_sessions_complete: true }
  platform.request.mockResolvedValueOnce(response(parked))
  await expect(analysisApi.getParkedDetail(car, '1', '2')).resolves.toMatchObject({ detail: { chargeSessionsComplete: true } })
})

it('normalizes rated-range/average speed/temperature from the same actual drive response and cache reconstruction', async () => {
  const { car, matelinkApi } = await setup()
  platform.request.mockResolvedValueOnce(response({ units: { unit_of_length: 'mi', unit_of_temperature: 'F' }, drives: [{ drive_id: 1, source: 'teslamate_archive',
    range_rated: { start_range: 100, end_range: 90 }, speed_avg: 30, outside_temp_avg: 32 }] }))
  const drive = (await matelinkApi.getDrives(car.id, 1, 20, car)).items[0]
  expect(drive).toMatchObject({ startRatedRangeKm: 160.9344, endRatedRangeKm: 144.84096, speedAvgKmh: 48.28032, outsideTempC: 0 })
  const { historyCacheKey, writeHistoryCache, readHistoryCache } = await import('./history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, value) }
  const key = historyCacheKey('drives', 'a', 'uid-a', 'https://api.example.test')
  writeHistoryCache(storage, key, [drive])
  expect(readHistoryCache<typeof drive>(storage, key)[0]).toMatchObject({ startRatedRangeKm: 160.9344, outsideTempC: 0 })
})

it('reads actual nested charge samples once, normalizes temperature and keeps session energy qualified and private arrays online', async () => {
  const { car, analysisApi } = await setup()
  const before = JSON.stringify([...platform.values])
  platform.request.mockResolvedValueOnce(response({ units: { unit_of_temperature: 'F', unit_of_length: 'mi' }, car: { car_id: 7 }, charge: {
    charge_id: 3, source: 'teslamate_archive', quality_state: 'observed', start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T01:00:00Z',
    charge_energy_added: 5, charge_energy_used: 6, cost: 0, currency: 'USD', charger_voltage: 0, charger_actual_current: 0, charger_phases: 3,
    charge_current_request: 16, time_to_full_charge: 0, outside_temp_avg: 32, odometer: 10, range_rated: { start_range: 10, end_range: 20 },
    charge_details: [
      { date: '2026-10-14T00:30:00Z', battery_level: 0, outside_temp: -4, charger_details: { charger_power: 0, charger_voltage: 0, charger_actual_current: 0 } },
      { date: '2026-10-14T00:00:00Z', battery_level: 20, outside_temp: 32, charger_details: { charger_power: -2, charger_voltage: 230, charger_actual_current: -1 } },
      { date: 'invalid', battery_level: 30 }, { date: '2026-10-14T02:00:00Z', battery_level: 99 },
    ],
  } }))
  const evidence = await analysisApi.getChargeEvidence(car, '3')
  expect(platform.request).toHaveBeenCalledTimes(2)
  expect(evidence.charge).toMatchObject({ id: '3', energyAddedKwh: 5, energyUsedKwh: 6, cost: 0, currency: 'USD' })
  expect(evidence.points).toHaveLength(2)
  expect(evidence.points[0]).toMatchObject({ batteryLevel: 20, temperatureC: 0, powerKw: -2, voltageV: 230, currentA: -1 })
  expect(evidence.points[1]).toMatchObject({ batteryLevel: 0, temperatureC: -20, powerKw: 0, voltageV: 0, currentA: 0 })
  expect(evidence.summary).toMatchObject({ chargerVoltageV: 0, currentA: 0, phases: 3, currentRequestA: 16, timeToFullHours: 0, outsideTempC: 0, odometerKm: 16.09344 })
  expect(evidence.pointsComplete).toBe(false)
  expect(JSON.stringify([...platform.values])).toBe(before)
})

it('charge curves cannot revive explicitly unknown session energy or admit quarantined/foreign detail', async () => {
  const { car, analysisApi } = await setup()
  const charge = { charge_id: 3, source: 'telemetry_mqtt', start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T01:00:00Z',
    charge_energy_added: 99, charge_energy_used: 100, energy_contract: { version: 1, battery_input: { quality: 'unknown' }, ac_input: { quality: 'unknown' } },
    charge_details: [{ date: '2026-10-14T00:30:00Z', outside_temp: 32, charger_details: { charger_power: 40 } }] }
  platform.request.mockResolvedValueOnce(response({ units: { unit_of_temperature: 'unsupported' }, charge }))
  const result = await analysisApi.getChargeEvidence(car, '3')
  expect(result.charge).toMatchObject({ energyAddedKwh: null, energyUsedKwh: null })
  expect(result.points[0]).toMatchObject({ powerKw: 40, voltageV: null, currentA: null, temperatureC: null })
  platform.request.mockResolvedValueOnce(response({ charge: { ...charge, quality_state: 'quarantined', charger_voltage: 230 } }))
  await expect(analysisApi.getChargeEvidence(car, '3')).resolves.toMatchObject({ points: [], summary: { chargerVoltageV: null }, reason: 'quarantined_history' })
  platform.request.mockResolvedValueOnce(response({ car: { car_id: 8 }, charge }))
  await expect(analysisApi.getChargeEvidence(car, '3')).rejects.toMatchObject({ code: 'vehicle_not_found' })
  platform.request.mockResolvedValueOnce(response({ charge: { ...charge, charge_id: 4 } }))
  await expect(analysisApi.getChargeEvidence(car, '3')).rejects.toMatchObject({ code: 'invalid_api_response' })
  platform.request.mockResolvedValueOnce(response({ charge: null }))
  await expect(analysisApi.getChargeEvidence(car, '3')).resolves.toMatchObject({ charge: null, points: [], reason: 'detail_unavailable' })
})

it('bounds charge samples and rejects a delayed former account response', async () => {
  const { car, analysisApi } = await setup()
  platform.request.mockResolvedValueOnce(response({ charge: { charge_id: 3, source: 'telemetry_mqtt', start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T01:00:00Z',
    charge_details: Array.from({ length: 20_001 }, () => ({ date: '2026-10-14T00:30:00Z', battery_level: 50 })) } }))
  const result = await analysisApi.getChargeEvidence(car, '3')
  expect(result.points).toHaveLength(20_000)
  expect(result).toMatchObject({ pointsComplete: false, reason: 'charge_sample_limit' })
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const pending = analysisApi.getChargeEvidence(car, '3')
  platform.values.set('matelink.wechat.session.v1', session('b'))
  release(response({ charge: { charge_id: 3 } }))
  await expect(pending).rejects.toMatchObject({ code: 'session_changed' })
})
