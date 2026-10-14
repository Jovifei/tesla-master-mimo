import { beforeEach, expect, it, vi } from 'vitest'

const fake = vi.hoisted(() => ({ request: vi.fn(), storage: new Map<string, unknown>() }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: fake.request, getStorageSync: (key: string) => fake.storage.get(key),
  setStorageSync: (key: string, value: unknown) => fake.storage.set(key, value),
  removeStorageSync: (key: string) => fake.storage.delete(key),
  getStorageInfoSync: () => ({ keys: [...fake.storage.keys()] }),
} }))
const storage = { getStorageSync: (key: string) => fake.storage.get(key),
  setStorageSync: (key: string, value: unknown) => fake.storage.set(key, value),
  removeStorageSync: (key: string) => fake.storage.delete(key) }
beforeEach(() => { vi.resetModules(); fake.request.mockReset(); fake.storage.clear(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test') })
const response = (data: unknown) => ({ statusCode: 200, data, header: {} })

it.each(['http://api.example.test', 'https://a@api.example.test', 'https://api.example.test?x=1',
  'https://api.example.test#x', 'https://api.example.test/a', 'https://api..test',
  'https://api.example.test\\evil', 'https://api.example.test\n', 'https://%61pi.example.test',
  'https://api.example.test:0443', 'https://api.example.test:'])('rejects ambiguous API base before transport: %s', async origin => {
  vi.stubEnv('TARO_APP_API_BASE_URL', origin)
  const { matelinkApi } = await import('./api')
  await expect(matelinkApi.getCars()).rejects.toMatchObject({ code: 'api_not_configured' })
  expect(fake.request).not.toHaveBeenCalled()
})

it('never displays upstream messages or private network error values', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce({ statusCode: 500, data: { error: 'unknown_backend', message: 'SECRET_TOKEN' }, header: {} })
  await expect(matelinkApi.getCars()).rejects.not.toHaveProperty('message', 'SECRET_TOKEN')
  for (const code of ['constructor', '__proto__', 'toString']) {
    fake.request.mockResolvedValueOnce({ statusCode: 500, data: { error: code, message: 'SECRET_TOKEN' }, header: {} })
    await expect(matelinkApi.getCars()).rejects.toHaveProperty('message', 'MateLink API 请求失败')
  }
  fake.request.mockRejectedValueOnce(new Error('SECRET_URL_PASSWORD'))
  await expect(matelinkApi.getCars()).rejects.toMatchObject({ code: 'network_error', message: '网络暂时不可用，请稍后重试' })
})

it('status adapter preserves zero/false and rejects nonfinite/out-of-range/coerced data', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ status: {
    battery_details: { battery_level: 0, rated_battery_range: Infinity }, odometer: -1,
    car_geodata: { latitude: 0, longitude: 0 }, car_status: { locked: false, sentry_mode: 'false' },
    climate_details: { is_climate_on: 0, inside_temp: NaN },
    driving_details: { speed: -1, power: -10, heading: 361 }, charging_details: { charger_power: 0 },
  } }))
  await expect(matelinkApi.getCarStatus(7)).resolves.toMatchObject({ batteryLevel: 0, ratedRange: null,
    odometer: null, location: { latitude: 0, longitude: 0 }, locked: false, sentryMode: null,
    isClimateOn: null, insideTemp: null, speed: null, power: -10, heading: null, chargerPower: 0 })
})

it('actual getCars cache is scoped and offline cache cannot authorize remote history', async () => {
  const { matelinkApi } = await import('./api')
  const { writeAppSession } = await import('./session')
  const { readCarsCache } = await import('./cars_cache')
  writeAppSession(storage, { accessToken: 'fixture', refreshToken: null, expiresAt: null, userId: 'a', linkRequired: false })
  fake.request.mockResolvedValueOnce(response({ cars: [{ car_id: 7, vehicle_uid: 'vehicle-a', name: 'A' }] }))
  const fresh = await matelinkApi.getCars()
  expect(fresh[0].remoteVerified).toBe(true)
  const cached = readCarsCache(storage, 'a', 'https://api.example.test')
  expect(cached[0].remoteVerified).toBe(false)
  expect(readCarsCache(storage, 'b', 'https://api.example.test')).toEqual([])
  expect(readCarsCache(storage, 'a', 'https://other.example.test')).toEqual([])
  await expect(matelinkApi.getDrives(7, 1, 20, cached[0])).rejects.toMatchObject({ code: 'vehicle_not_found' })
  expect(fake.request).toHaveBeenCalledTimes(1)
})

it('adapter rejects supplied foreign identity and wrong detail ID, encodes dynamic IDs', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ cars: [{ car_id: 7, vehicle_uid: 'a' }] }))
  const [car] = await matelinkApi.getCars()
  fake.request.mockResolvedValueOnce(response({ drives: [{ drive_id: 4, car_id: 7, vehicle_uid: 'b' }] }))
  await expect(matelinkApi.getDrives(7, 1, 20, car)).rejects.toMatchObject({ code: 'vehicle_not_found' })
  fake.request.mockResolvedValueOnce(response({ drive: { drive_id: 'wrong' } }))
  await expect(matelinkApi.getDriveDetail(7, 'a/b?c', car)).rejects.toMatchObject({ code: 'history_unavailable' })
  expect(fake.request.mock.calls[2][0].url).toBe('https://api.example.test/api/v1/cars/7/drives/a%2Fb%3Fc')
})

it('invite adapters return the actual parsed response', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ valid: true })).mockResolvedValueOnce(response({ success: true }))
  await expect(matelinkApi.validateInviteCode('a')).resolves.toEqual({ valid: true })
  await expect(matelinkApi.claimInviteCode('a')).resolves.toEqual({ success: true })
})

it('late actual Taro response cannot populate another account cars cache', async () => {
  const { matelinkApi } = await import('./api')
  const { writeAppSession } = await import('./session')
  const { readCarsCache } = await import('./cars_cache')
  const session = (userId: string) => ({ accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
  writeAppSession(storage, session('a'))
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const pending = matelinkApi.getCars()
  writeAppSession(storage, session('b'))
  release(response({ cars: [{ car_id: 7, vehicle_uid: 'a' }] }))
  await expect(pending).rejects.toMatchObject({ code: 'session_changed' })
  expect(readCarsCache(storage, 'a', 'https://api.example.test')).toEqual([])
  expect(readCarsCache(storage, 'b', 'https://api.example.test')).toEqual([])
})

it('previous account fresh car proof cannot authorize the new account numeric ID', async () => {
  const { matelinkApi } = await import('./api')
  const { writeAppSession } = await import('./session')
  const session = (userId: string) => ({ accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
  writeAppSession(storage, session('a'))
  fake.request.mockResolvedValueOnce(response({ cars: [{ car_id: 7, vehicle_uid: 'a' }] }))
  const [car] = await matelinkApi.getCars()
  writeAppSession(storage, session('b'))
  await expect(matelinkApi.getDrives(7, 1, 20, car)).rejects.toMatchObject({ code: 'vehicle_not_found' })
  expect(fake.request).toHaveBeenCalledTimes(1)
})

it('charge adapter preserves missing versus explicit unknown currency and never assumes AC', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ charges: [
    { charge_id: 1, cost: 0, charger_power: 0 },
    { charge_id: 2, currency: 'ZZZ', charge_type: 'ac', fast_charger_present: true },
    { charge_id: 3, currency_code: 'CNY', cost_currency: 'USD' },
  ] }))
  const result = await matelinkApi.getCharges(7)
  expect(result.items[0]).toMatchObject({ cost: 0, chargerPowerKw: 0, chargeMode: null })
  expect(result.items[0].currency).toBeUndefined()
  expect(result.items[1].currency).toBeNull()
  expect(result.items[1].chargeMode).toBeNull()
  expect(result.items[2].currency).toBeNull()
})

it('actual wire-derived power claims cannot forge a qualified history receipt', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ drives: [{
    drive_id: 4, source: 'teslamate_archive', start_date: '2026-10-14T00:00:00Z',
    end_date: '2026-10-14T00:01:00Z', odometer_details: { odometer_distance: 2 },
    parsedEnergyVersion: 1,
    power_integral: { energyKwh: 99, coverageSeconds: 60, windowSeconds: 60, complete: true,
      reason: 'complete_power_window', startDate: '2026-10-14T00:00:00Z', endDate: '2026-10-14T00:01:00Z' },
  }] }))
  const { items } = await matelinkApi.getDrives(7)
  expect(items[0].energyConsumedKwh).toBeNull()
  expect(items[0].energy?.source).toBe('unavailable')
  expect(items[0].energyReceipt?.power_integral).toBeUndefined()
})
