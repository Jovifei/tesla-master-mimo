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
  writeAppSession(storage, { apiOrigin: 'https://api.example.test', accessToken: 'fixture', refreshToken: null, expiresAt: null, userId: 'a', linkRequired: false })
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
  const session = (userId: string) => ({ apiOrigin: 'https://api.example.test', accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
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
  const session = (userId: string) => ({ apiOrigin: 'https://api.example.test', accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
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

it.each([
  { statusCode: 302, data: {}, header: { Location: 'https://outside.example.test' } },
  { statusCode: 200, data: { cars: [] }, header: { location: 'https://outside.example.test' } },
  { statusCode: 200, data: '<html>login</html>', header: { 'Content-Type': 'text/html' } },
  { statusCode: 200, data: null, header: {} },
  { statusCode: 200, data: [], header: {} },
])('fails closed on redirects and non-JSON-object success without erasing session', async result => {
  const { matelinkApi } = await import('./api')
  const { writeAppSession, readAppSession } = await import('./session')
  writeAppSession(storage, { apiOrigin: 'https://api.example.test', accessToken: 'fixture', refreshToken: null, expiresAt: null, userId: 'a', linkRequired: false })
  fake.request.mockResolvedValueOnce(result)
  await expect(matelinkApi.getCars()).rejects.toMatchObject({ code: 'invalid_api_response' })
  expect(readAppSession(storage)?.userId).toBe('a')
  expect(fake.request).toHaveBeenCalledTimes(1)
})

it('status converts only same-response proven units and preserves zero and negative power through cache reopen', async () => {
  const { matelinkApi } = await import('./api')
  const { writeVehicleCache, readVehicleCache } = await import('./vehicle_cache')
  fake.request.mockResolvedValueOnce(response({ units: { unit_of_length: 'mi', unit_of_temperature: 'F' }, status: {
    odometer: 10, battery_details: { rated_battery_range: 20 }, driving_details: { speed: 0, power: -12 },
    climate_details: { inside_temp: 32, outside_temp: -4 }, car_status: { locked: false },
  } }))
  const status = await matelinkApi.getCarStatus(7)
  expect(status).toMatchObject({ odometer: 16.09344, ratedRange: 32.18688, speed: 0, power: -12, insideTemp: 0, outsideTemp: -20, locked: false })
  expect(writeVehicleCache(storage, 'a', 'https://api.example.test', 'uid', status)).toBe(true)
  expect(readVehicleCache(storage, 'a', 'https://api.example.test', 'uid')).toMatchObject({ odometer: 16.09344, insideTemp: 0 })
  fake.request.mockResolvedValueOnce(response({ units: { unit_of_length: 'yards', unit_of_temperature: null }, status: {
    odometer: 10, battery_details: { rated_battery_range: 20, battery_level: 0 }, driving_details: { speed: 30, power: -12 },
    climate_details: { inside_temp: 20, outside_temp: 21 },
  } }))
  await expect(matelinkApi.getCarStatus(7)).resolves.toMatchObject({ odometer: null, ratedRange: null, speed: null,
    insideTemp: null, outsideTemp: null, batteryLevel: 0, power: -12 })
})

it('real parser never guesses SOC capacity for unknown vehicles, including receipt cache reconstruction', async () => {
  const { matelinkApi } = await import('./api')
  const { historyCacheKey, readHistoryCache, writeHistoryCache } = await import('./history_cache')
  const { resolveDriveEnergy } = await import('../domain/drive_energy')
  fake.request.mockResolvedValueOnce(response({ cars: [{ car_id: 7, vehicle_uid: 'uid', model: 'unknown' }] }))
  const [car] = await matelinkApi.getCars()
  fake.request.mockResolvedValueOnce(response({ drives: [{ drive_id: 9, source: 'telemetry_mqtt',
    start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T00:10:00Z',
    odometer_details: { odometer_distance: 10 }, battery_details: { start_battery_level: 80, end_battery_level: 78 } }] }))
  const [drive] = (await matelinkApi.getDrives(7, 1, 20, car)).items
  expect(drive.energyConsumedKwh).toBeNull()
  expect(drive.energy?.reason).toBe('capacity_basis_unavailable')
  const key = historyCacheKey('drives', 'a', 'uid', 'https://api.example.test')
  writeHistoryCache(storage, key, [drive])
  const reopened = readHistoryCache<typeof drive>(storage, key)[0]
  expect(resolveDriveEnergy(reopened.energyReceipt!, car).energyKwh).toBeNull()
  expect(resolveDriveEnergy(reopened.energyReceipt!, { ...car, capacityKwh: 70 }).energyKwh).toBeCloseTo(1.4)
})

it.each(['cars', 'drives', 'charges'] as const)('rejects a malformed %s payload instead of replacing good data with empty success', async kind => {
  const { matelinkApi } = await import('./api')
  for (const data of [{}, { [kind]: null }, { [kind]: {} }]) {
    fake.request.mockResolvedValueOnce(response(data))
    const request = kind === 'cars' ? matelinkApi.getCars() : kind === 'drives' ? matelinkApi.getDrives(7) : matelinkApi.getCharges(7)
    await expect(request).rejects.toMatchObject({ code: 'invalid_api_response' })
  }
})

it('actual status parser retains per-wheel evidence through scoped cache rebuilding without borrowing package time', async () => {
  const { matelinkApi } = await import('./api')
  const { writeVehicleCache, readVehicleCache } = await import('./vehicle_cache')
  vi.useFakeTimers()
  try {
    vi.setSystemTime(new Date('2026-10-15T08:01:00Z'))
    fake.request.mockResolvedValueOnce(response({ observed_at: '2026-10-15T08:01:00Z', source: 'telemetry_mqtt', status: {
      tpms_details: { pressure_unit: 'bar', source: 'telemetry_mqtt', time_basis: 'collector_received_at',
        tpms_pressure_fl: 0, tpms_pressure_fl_observed_at: '2026-10-15T08:00:00Z', tpms_pressure_fr: 2.6,
        tpms_pressure_rl: 2.7, tpms_pressure_rl_observed_at: '2026-10-15T07:40:00Z' },
    } }))
    const status = await matelinkApi.getCarStatus(7)
    expect(status.tpms?.wheels.fl).toMatchObject({ pressureBar: 0, observedAt: '2026-10-15T08:00:00.000Z', state: 'observed' })
    expect(status.tpms?.wheels.fr).toMatchObject({ pressureBar: null, observedAt: null, reason: 'missing_or_invalid_wheel_time' })
    expect(status.tpms?.wheels.rl.state).toBe('stale')
    writeVehicleCache(storage, 'a', 'https://api.example.test', 'uid', status)
    vi.setSystemTime(new Date('2026-10-15T08:20:00Z'))
    const reopened = readVehicleCache(storage, 'a', 'https://api.example.test', 'uid')!
    expect(reopened.tpms?.wheels.fl).toMatchObject({ pressureBar: 0, observedAt: '2026-10-15T08:00:00.000Z', state: 'stale' })
    expect(reopened.tpms?.wheels.fr.observedAt).toBeNull()
  } finally { vi.useRealTimers() }
})

it.each([undefined, 'https://previous.example.test'])('does not send a session or link credential from an unbound/foreign origin %s', async apiOrigin => {
  const { matelinkApi } = await import('./api')
  const { SESSION_KEY, PENDING_LINK_KEY, readAppSession, readPendingWechatLink } = await import('./session')
  const legacy = { accessToken: 'private-old-access', refreshToken: 'private-old-refresh', userId: 'a', apiOrigin }
  const link = { linkToken: 'private-old-link', expiresAt: null, apiOrigin }
  fake.storage.set(SESSION_KEY, legacy)
  fake.storage.set(PENDING_LINK_KEY, link)
  expect(readAppSession(storage)).toBeNull()
  expect(readPendingWechatLink(storage)).toBeNull()
  fake.request.mockResolvedValueOnce(response({ cars: [] }))
  await matelinkApi.getCars()
  expect(fake.request.mock.calls[0][0].header).not.toHaveProperty('Authorization')
  fake.request.mockResolvedValueOnce(response({ authorization_url: 'https://auth.tesla.com/oauth2/v3/authorize',
    transaction_id: 'fixture-transaction', client_proof: 'fixture-proof' }))
  await matelinkApi.startTeslaAuthorization({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })
  expect(fake.request.mock.calls[1][0].header).not.toHaveProperty('X-WeChat-Link-Token')
  expect(fake.storage.get(SESSION_KEY)).toBe(legacy)
  expect(fake.storage.get(PENDING_LINK_KEY)).toBe(link)
})

it('a changed-origin build never forwards the previous build session after module reconstruction', async () => {
  const { writeAppSession, SESSION_KEY } = await import('./session')
  const previous = { apiOrigin: 'https://api.example.test', accessToken: 'fixture-old-origin', refreshToken: 'fixture-refresh',
    expiresAt: null, userId: 'a', linkRequired: false }
  writeAppSession(storage, previous)
  vi.resetModules()
  vi.stubEnv('TARO_APP_API_BASE_URL', 'https://new.example.test')
  const { matelinkApi, refreshSession } = await import('./api')
  const { readAppSession } = await import('./session')
  expect(readAppSession(storage)).toBeNull()
  fake.request.mockResolvedValueOnce(response({ cars: [] }))
  await matelinkApi.getCars()
  expect(fake.request.mock.calls[0][0]).toMatchObject({ url: 'https://new.example.test/api/v1/cars' })
  expect(fake.request.mock.calls[0][0].header).not.toHaveProperty('Authorization')
  await expect(refreshSession()).rejects.toMatchObject({ code: 'session_required' })
  expect(fake.request).toHaveBeenCalledTimes(1)
  expect(fake.storage.get(SESSION_KEY)).toBe(previous)
})

function newAccountArchives(user: string, origin = 'https://api.example.test') {
  const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E'), owner = `${encode(origin)}.${encode(user)}`
  const entries = ['tpms', 'events', 'manual-trips', 'charge-cost', 'tpms-profile'].map(namespace => {
    const key = `matelink.${namespace}.v1.${owner}.uid${namespace === 'tpms' ? '.telemetry_mqtt' : namespace === 'events' ? '.drives' : ''}`
    const value = { schema: 1, scope: key, points: [], sources: {}, trips: [], rows: [], source: 'user_configured', profile: { enabled: true, targetBar: 2.9, lowBar: 2.6, highBar: 3.4, changeEnabled: false, deltaBar: .3, windowHours: 24 } }
    fake.storage.set(key, value); return key
  })
  const tariff = `matelink.tariff.v1.${owner}`
  fake.storage.set(tariff, { currency: 'CNY', peak: 1, flat: .7, valley: 0, timeZone: 'Asia/Shanghai', source: 'user_configured', estimateOnly: true })
  return [...entries, tariff]
}

it('only server-confirmed current account deletion removes its new caches and normal logout preserves them', async () => {
  const { matelinkApi } = await import('./api'), { writeAppSession } = await import('./session')
  const own = newAccountArchives('a'), other = newAccountArchives('a.b'), foreign = newAccountArchives('a', 'https://other.example.test')
  const unknown = own[0].replace('.v1.', '.v9.'), ambiguous = own[2].replace('.uid', '.b.uid'), wrongSchema = own[3].replace('.uid', '.future')
  for (const key of [unknown, ambiguous]) fake.storage.set(key, { schema: 1, scope: key, trips: [], points: [] })
  fake.storage.set(wrongSchema, { schema: 9, scope: wrongSchema, rows: [] })
  const login = () => writeAppSession(storage, { apiOrigin: 'https://api.example.test', accessToken: 'fixture', refreshToken: null, expiresAt: null, userId: 'a', linkRequired: false })
  login(); fake.request.mockResolvedValueOnce(response({ status: 'pending' }))
  await expect(matelinkApi.deleteAccount()).rejects.toMatchObject({ code: 'invalid_account_delete_response' })
  expect(own.every(key => fake.storage.has(key))).toBe(true)
  fake.request.mockResolvedValueOnce({ statusCode: 503, data: {}, header: {} })
  await expect(matelinkApi.deleteAccount()).rejects.toBeDefined()
  expect(own.every(key => fake.storage.has(key))).toBe(true)
  fake.request.mockResolvedValueOnce(response({ status: 'ok' })); await matelinkApi.logout()
  expect(own.every(key => fake.storage.has(key))).toBe(true)
  login(); fake.request.mockResolvedValueOnce(response({ status: 'deleted' })); await matelinkApi.deleteAccount()
  expect(own.some(key => fake.storage.has(key))).toBe(false)
  expect([...other, ...foreign, unknown, ambiguous, wrongSchema].every(key => fake.storage.has(key))).toBe(true)
})

it('a late account deletion callback cannot clear another active account session or new caches', async () => {
  const { matelinkApi } = await import('./api'), { writeAppSession, readAppSession } = await import('./session')
  const own = newAccountArchives('a'), other = newAccountArchives('b')
  const login = (userId: string) => writeAppSession(storage, { apiOrigin: 'https://api.example.test', accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
  login('a'); let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const pending = matelinkApi.deleteAccount(); login('b'); release(response({ status: 'deleted' }))
  await expect(pending).rejects.toMatchObject({ code: 'session_changed' })
  expect(readAppSession(storage)?.userId).toBe('b')
  expect([...own, ...other].every(key => fake.storage.has(key))).toBe(true)
})

const pendingAuth = (id = 'txn') => ({ transactionId: id, clientProof: `proof-${id}`, apiOrigin: 'https://api.example.test', expiresAt: null })
it('only the newest anonymous authorization start may replace the local transaction', async () => {
  const { matelinkApi } = await import('./api')
  const { readPendingTeslaAuthorization } = await import('./session')
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    .mockResolvedValueOnce(response({ authorization_url: 'https://auth.tesla.com/authorize', transaction_id: 'new', client_proof: 'new-proof' }))
  const consent = { termsVersion: '2026-08-21', privacyVersion: '2026-08-21' }
  const old = matelinkApi.startTeslaAuthorization(consent)
  await matelinkApi.startTeslaAuthorization(consent)
  release(response({ authorization_url: 'https://auth.tesla.com/authorize', transaction_id: 'old', client_proof: 'old-proof' }))
  await expect(old).rejects.toMatchObject({ code: 'session_changed' })
  expect(readPendingTeslaAuthorization(storage)?.transactionId).toBe('new')
})
it.each(['http://auth.tesla.com/authorize', 'https://evil.example.test/authorize',
  'https://api.example.test/oauth/wechat/authorize', 'https://auth.tesla.com/user/revoke/consent'])('refuses a non-login authorization entry: %s', async url => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ authorization_url: url, transaction_id: 'txn', client_proof: 'proof' }))
  await expect(matelinkApi.startTeslaAuthorization({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).rejects.toMatchObject({ code: 'invalid_authorization_response' })
})
it('cancelling fences a claim before its network response can resurrect the session', async () => {
  const { matelinkApi } = await import('./api')
  const { writePendingTeslaAuthorization, readAppSession } = await import('./session')
  writePendingTeslaAuthorization(storage, pendingAuth())
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    .mockResolvedValueOnce(response({ status: 'cancelled' }))
  const claim = matelinkApi.claimTeslaAuthorization()
  await matelinkApi.cancelTeslaAuthorization()
  release(response({ access_token: 'late-access', user: { id: 'a' } }))
  await expect(claim).rejects.toMatchObject({ code: 'session_changed' })
  expect(readAppSession(storage)).toBeNull()
})
it('deduplicates same transaction claims and never sends callback messages or WeChat identity', async () => {
  const { matelinkApi } = await import('./api')
  const { writePendingTeslaAuthorization } = await import('./session')
  writePendingTeslaAuthorization(storage, pendingAuth())
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const first = matelinkApi.claimTeslaAuthorization(), second = matelinkApi.claimTeslaAuthorization()
  expect(fake.request).toHaveBeenCalledTimes(1)
  expect(fake.request.mock.calls[0][0]).toMatchObject({ url: 'https://api.example.test/v1/auth/miniprogram/claim',
    data: { transaction_id: 'txn', client_proof: 'proof-txn' }, header: { 'Content-Type': 'application/json' } })
  release(response({ access_token: 'access', user: { id: 'same-tesla-user' } }))
  expect((await first).userId).toBe('same-tesla-user')
  expect((await second).userId).toBe('same-tesla-user')
})
it('rejects late status after transaction replacement, including late errors', async () => {
  const { matelinkApi } = await import('./api')
  const { writePendingTeslaAuthorization } = await import('./session')
  writePendingTeslaAuthorization(storage, pendingAuth('a'))
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const status = matelinkApi.getTeslaAuthorizationStatus()
  writePendingTeslaAuthorization(storage, pendingAuth('b'))
  release({ statusCode: 410, data: { error: 'authorization_expired' }, header: {} })
  await expect(status).rejects.toMatchObject({ code: 'session_changed' })
})

it('cancels the captured Tesla transaction before deleting the account', async () => {
  const { matelinkApi } = await import('./api')
  const { writePendingTeslaAuthorization, writeAppSession, readAppSession } = await import('./session')
  writeAppSession(storage, { apiOrigin: 'https://api.example.test', accessToken: 'a-access', refreshToken: null, expiresAt: null, userId: 'a', linkRequired: false })
  writePendingTeslaAuthorization(storage, pendingAuth())
  fake.request.mockImplementation(async (options: { url: string }) => response(options.url.endsWith('/cancel') ? { status: 'cancelled' } : { status: 'deleted' }))
  await matelinkApi.deleteAccount()
  expect(fake.request.mock.calls.map(call => call[0].url)).toEqual([
    'https://api.example.test/v1/auth/miniprogram/cancel', 'https://api.example.test/v1/account'])
  expect(readAppSession(storage)).toBeNull()
})
it.each([401, 500])('does not delete an account when cancelling its pending Tesla authorization fails with %s', async statusCode => {
  const { matelinkApi } = await import('./api')
  const { writePendingTeslaAuthorization, writeAppSession, readAppSession } = await import('./session')
  writeAppSession(storage, { apiOrigin: 'https://api.example.test', accessToken: 'a-access', refreshToken: null, expiresAt: null, userId: 'a', linkRequired: false })
  writePendingTeslaAuthorization(storage, pendingAuth())
  fake.request.mockResolvedValueOnce({ statusCode, data: { error: 'invalid_authorization_transaction' }, header: {} })
  await expect(matelinkApi.deleteAccount()).rejects.toBeInstanceOf(Error)
  expect(fake.request).toHaveBeenCalledTimes(1)
  expect(fake.request.mock.calls[0][0].url).toContain('/cancel')
  expect(readAppSession(storage)?.userId).toBe('a')
})
it('prevents new Tesla authorization while account deletion is in flight', async () => {
  const { matelinkApi } = await import('./api')
  const { writeAppSession } = await import('./session')
  writeAppSession(storage, { apiOrigin: 'https://api.example.test', accessToken: 'a-access', refreshToken: null, expiresAt: null, userId: 'a', linkRequired: false })
  let release!: (value: unknown) => void
  fake.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const deletion = matelinkApi.deleteAccount()
  await expect(matelinkApi.startTeslaAuthorization({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).rejects.toMatchObject({ code: 'session_changed' })
  release(response({ status: 'deleted' }))
  await deletion
  expect(fake.request).toHaveBeenCalledTimes(1)
})
