import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@tarojs/components', () => ({ Text: 'Text', View: 'View' }))

const mocks = vi.hoisted(() => ({
  request: vi.fn(),
  login: vi.fn(),
  storage: new Map<string, unknown>(),
}))

vi.mock('@tarojs/taro', () => ({
  default: {
    request: mocks.request,
    login: mocks.login,
    getStorageSync: (key: string) => mocks.storage.get(key),
    setStorageSync: (key: string, value: unknown) => mocks.storage.set(key, value),
    removeStorageSync: (key: string) => mocks.storage.delete(key),
    getStorageInfoSync: () => ({ keys: [...mocks.storage.keys()] }),
  },
}))

async function modules() {
  vi.resetModules()
  vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  return {
    api: await import('./api'),
    session: await import('./session'),
  }
}

beforeEach(() => {
  mocks.request.mockReset()
  mocks.login.mockReset()
  mocks.storage.clear()
})

const storageAdapter = {
  getStorageSync: (key: string) => mocks.storage.get(key),
  setStorageSync: (key: string, value: unknown) => mocks.storage.set(key, value),
  removeStorageSync: (key: string) => mocks.storage.delete(key),
}

describe('MateLink API transport', () => {
  it('starts anonymous Tesla authorization without requesting a WeChat identity', async () => {
    const { api, session } = await modules()
    mocks.request.mockResolvedValueOnce({ statusCode: 200, data: {
      authorization_url: 'https://auth.tesla.cn/oauth2/v3/authorize?state=opaque',
      transaction_id: 'mini-txn', client_proof: 'mini-proof', expires_at: '2026-10-18T10:10:00Z',
    }, header: {} })
    await expect(api.matelinkApi.startTeslaAuthorization({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).resolves.toMatchObject({ transaction_id: 'mini-txn' })
    expect(mocks.login).not.toHaveBeenCalled()
    expect(mocks.request).toHaveBeenCalledTimes(1)
    expect(mocks.request.mock.calls[0][0].header).toEqual({
      'X-JourVolt-Terms-Version': '2026-08-21', 'X-JourVolt-Privacy-Version': '2026-08-21',
      'X-MiniProgram-Channel': 'wechat',
    })
    expect(session.readAppSession(storageAdapter)).toBeNull()
    expect(session.readPendingTeslaAuthorization(storageAdapter)).toMatchObject({ transactionId: 'mini-txn', clientProof: 'mini-proof' })
  })

  it.each(['list', 'detail'] as const)('normalizes response miles exactly once through %s, cache and receipt replay', async endpoint => {
    const { api } = await modules()
    const raw = { drive_id: 1, source: 'teslamate_archive', start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T00:10:00Z',
      energy_consumed_net: -2, odometer_details: { odometer_distance: 10 },
      speed_max: 60, speed_sample_count: 2, speed_metrics_source: 'observed_route_samples' }
    mocks.request.mockResolvedValue({ statusCode: 200, data: { data: { units: { unit_of_length: 'mi' },
      ...(endpoint === 'list' ? { drives: [raw] } : { drive: raw }) } }, header: {} })
    const drive = endpoint === 'list' ? (await api.matelinkApi.getDrives(7)).items[0] : (await api.matelinkApi.getDriveDetail(7, '1'))!
    expect(drive.distanceKm).toBeCloseTo(16.09344, 12)
    expect(drive.efficiencyWhKm).toBeCloseTo(-2000 / 16.09344, 12)
    expect(drive.energyConsumedKwh).toBe(-2)
    expect(drive.speedMaxKmh).toBeCloseTo(96.56064, 12)
    expect(drive.speedUnit).toBe('km/h')
    const { peakSpeedKmh } = await import('../components/MetricValue')
    expect(peakSpeedKmh(drive)).toBeCloseTo(96.56064, 12)
    expect(drive.energyReceipt).toMatchObject({ source: 'teslamate_archive', distance_unit: 'mi', odometer_details: { odometer_distance: 10 } })
    expect(raw.odometer_details.odometer_distance).toBe(10)
    const cache = await import('./history_cache')
    const key = cache.historyCacheKey('drives', 'test-user', 'test-car', 'https://api.example.test')
    expect(cache.writeHistoryCache(storageAdapter, key, [drive])).toBe(true)
    const reopened = cache.readHistoryCache<typeof drive>(storageAdapter, key)[0]
    const energy = await import('../domain/drive_energy')
    expect(energy.driveDistanceKm(reopened.energyReceipt!)).toBeCloseTo(16.09344, 12)
    expect(energy.resolveDriveEnergy(reopened.energyReceipt!).efficiencyWhKm).toBeCloseTo(-2000 / 16.09344, 12)
    const merged = energy.mergeDriveEnergyRecord(reopened, drive, reopened)
    expect(merged.distanceKm).toBeCloseTo(16.09344, 12)
    expect(merged.efficiencyWhKm).toBeCloseTo(-2000 / 16.09344, 12)
    expect(merged.energyReceipt?.odometer_details?.odometer_distance).toBe(10)
  })
  it.each([
    { unit: 'km', distance: 10, energy: 2, expectedDistance: 10, expectedEfficiency: 200 },
    { unit: 'mi', distance: 10, energy: 0, expectedDistance: 16.09344, expectedEfficiency: 0 },
    { unit: 'km', distance: 0, energy: -2, expectedDistance: 0, expectedEfficiency: null },
    { unit: 'mi', distance: -1, energy: 2, expectedDistance: null, expectedEfficiency: null },
    { unit: 'unsupported', distance: 10, energy: 2, expectedDistance: null, expectedEfficiency: null },
    { unit: null, distance: 10, energy: -2, expectedDistance: null, expectedEfficiency: null },
  ])('respects the actual response length unit $unit with distance $distance and energy $energy', async input => {
    const { api } = await modules()
    mocks.request.mockResolvedValue({ statusCode: 200, data: { data: { units: { unit_of_length: input.unit }, drives: [{ drive_id: 1,
      energy_consumed_net: input.energy, odometer_details: { odometer_distance: input.distance },
      speed_unit: 'km/h', speed_max: 60, speed_sample_count: 2, speed_metrics_source: 'observed_route_samples' }] } }, header: {} })
    const drive = (await api.matelinkApi.getDrives(7)).items[0]
    expect(drive.distanceKm).toBe(input.expectedDistance)
    expect(drive.efficiencyWhKm).toBe(input.expectedEfficiency)
    expect(drive.energyConsumedKwh).toBe(input.energy)
    if (input.unit !== 'km' && input.unit !== 'mi') expect(drive.speedMaxKmh).toBeNull()
  })
  it('retains the cloud km contract when units are absent', async () => {
    const { api } = await modules()
    mocks.request.mockResolvedValue({ statusCode: 200, data: { data: { drives: [{ drive_id: 1, energy_consumed_net: 2,
      odometer_details: { odometer_distance: 10 } }] } }, header: {} })
    const drive = (await api.matelinkApi.getDrives(7)).items[0]
    expect(drive.distanceKm).toBe(10)
    expect(drive.efficiencyWhKm).toBe(200)
    expect(drive.energyReceipt?.distance_unit).toBe('km')
  })
  it('reprojects a cached mi detail after km correction, preserves sparse raw evidence and revokes unknown-unit peaks', async () => {
    const { api } = await modules()
    const raw = { drive_id: 1, source: 'teslamate_archive', start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T00:10:00Z',
      energy_consumed_net: 2, odometer_details: { odometer_distance: 10 },
      speed_max: 60, speed_sample_count: 2, speed_metrics_source: 'observed_route_samples' }
    const respond = (unit: unknown, value: object) => mocks.request.mockResolvedValue({ statusCode: 200,
      data: { data: { units: { unit_of_length: unit }, drives: [value] } }, header: {} })
    respond('mi', raw)
    const prior = (await api.matelinkApi.getDrives(7)).items[0]
    const history = await import('../domain/history')
    respond('km', { ...raw, odometer_details: undefined, speed_max: undefined, speed_sample_count: undefined, speed_metrics_source: undefined })
    const sparse = (await api.matelinkApi.getDrives(7)).items[0]
    const retained = history.mergeHistoryEvidence(prior, sparse)
    expect(retained.distanceKm).toBeCloseTo(16.09344, 12)
    expect(retained.energyReceipt?.distance_unit).toBe('mi')
    respond('km', raw)
    const corrected = history.mergeHistoryEvidence(retained, (await api.matelinkApi.getDrives(7)).items[0])
    expect(corrected.distanceKm).toBe(10)
    expect(corrected.efficiencyWhKm).toBe(200)
    expect(corrected.speedMaxKmh).toBe(60)
    respond('unknown', raw)
    const revoked = history.mergeHistoryEvidence(corrected, (await api.matelinkApi.getDrives(7)).items[0])
    expect(revoked.distanceKm).toBeNull()
    expect(revoked.efficiencyWhKm).toBeNull()
    expect(revoked.speedMaxKmh).toBeNull()
    expect(revoked.energyConsumedKwh).toBe(2)
  })
  it.each([
    { speed: 0, count: 1, source: 'observed_route_samples', unit: 'km/h', expected: 0 },
    { speed: 82, count: 3, source: 'observed_route_samples', unit: 'kph', expected: 82 },
    { speed: 82, count: 0, source: 'observed_route_samples', unit: 'km/h', expected: null },
    { speed: 82, count: undefined, source: 'observed_route_samples', unit: 'km/h', expected: null },
    { speed: 82, count: 2, source: 'unknown', unit: 'km/h', expected: null },
    { speed: 82, count: 2, source: 'observed_route_samples', unit: undefined, expected: null },
    { speed: 82, count: 2, source: 'observed_route_samples', unit: 'mph', expected: 82 * 1.609344 },
    { speed: -1, count: 2, source: 'observed_route_samples', unit: 'km/h', expected: null },
    { speed: null, count: 2, source: 'observed_route_samples', unit: 'km/h', expected: null },
  ])('reads drive peak only with its observed sample and unit proof: $speed / $count / $source / $unit', async row => {
    const { api } = await modules()
    mocks.request.mockResolvedValue({ statusCode: 200, data: { data: { drives: [{ drive_id: 1,
      speed_max: row.speed, speed_sample_count: row.count, speed_metrics_source: row.source, speed_unit: row.unit,
      speed_avg: 999, duration_min: 1, odometer_details: { odometer_distance: 999 } }] } }, header: {} })
    const actual = (await api.matelinkApi.getDrives(7)).items[0]
    expect(actual.speedMaxKmh).toBe(row.expected)
    expect(actual.speedMetricsSource).toBe(row.source)
    expect(actual.speedSampleCount).toBe(row.count ?? null)
  })
  it.each(['telemetry_mqtt', 'teslamate_archive', 'unknown'])('uses missing speed_unit only for proven normalized route source %s', async source => {
    const { api } = await modules()
    mocks.request.mockResolvedValue({ statusCode: 200, data: { data: { drives: [{ drive_id: 1,
      source, quality_state: 'observed', speed_max: 0, speed_sample_count: 1, speed_metrics_source: 'observed_route_samples' }] } }, header: {} })
    const actual = (await api.matelinkApi.getDrives(7)).items[0]
    expect(actual.speedMaxKmh).toBe(source === 'telemetry_mqtt' ? 0 : null)
    expect(actual.speedUnit).toBe(source === 'telemetry_mqtt' ? 'km/h' : null)
  })
  it('preserves HTTP error code, message key and Retry-After', async () => {
    const { api } = await modules()
    mocks.request.mockResolvedValue({
      statusCode: 429,
      data: { error: 'tesla_rate_limited', message_key: 'retry_later' },
      header: { 'Retry-After': '60' },
    })

    await expect(api.requestJson('/api/v1/cars')).rejects.toMatchObject({
      status: 429,
      code: 'tesla_rate_limited',
      messageKey: 'retry_later',
      retryAfter: '60',
    })
  })

  it('refreshes once after 401 and retries with the rotated access token', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'old-access',
      refreshToken: 'old-refresh',
      expiresAt: null,
      userId: 'user-a',
      linkRequired: false,
    })
    mocks.request
      .mockResolvedValueOnce({ statusCode: 401, data: { error: 'session_expired' }, header: {} })
      .mockResolvedValueOnce({ statusCode: 200, data: { access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 900 }, header: {} })
      .mockResolvedValueOnce({ statusCode: 200, data: { data: { cars: [] } }, header: {} })

    await expect(api.requestJson('/api/v1/cars')).resolves.toEqual({ data: { cars: [] } })
    expect(mocks.request).toHaveBeenCalledTimes(3)
    expect(mocks.request.mock.calls[1][0].data).toEqual({ refresh_token: 'old-refresh' })
    expect(mocks.request.mock.calls[2][0].header.Authorization).toBe('Bearer new-access')
    expect(session.readAppSession(storageAdapter)?.accessToken).toBe('new-access')
    expect(session.readAppSession(storageAdapter)?.userId).toBe('user-a')
  })

  it('does not recursively refresh when the refresh endpoint is unauthorized', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'old-access',
      refreshToken: 'old-refresh',
      expiresAt: null,
      userId: 'user-a',
      linkRequired: false,
    })
    mocks.request
      .mockResolvedValueOnce({ statusCode: 401, data: { error: 'session_expired' }, header: {} })
      .mockResolvedValueOnce({ statusCode: 401, data: { error: 'refresh_revoked' }, header: {} })

    await expect(api.requestJson('/api/v1/cars')).rejects.toMatchObject({ code: 'refresh_revoked', status: 401 })
    expect(mocks.request).toHaveBeenCalledTimes(2)
    expect(session.readAppSession(storageAdapter)).toBeNull()
  })

  it('does not resurrect a session when logout wins a refresh race', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'old-access', refreshToken: 'old-refresh', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    let resolveRefresh!: (value: unknown) => void
    const refreshResponse = new Promise(resolve => { resolveRefresh = resolve })
    mocks.request.mockImplementation((options: { url: string }) => {
      if (options.url.endsWith('/api/v1/cars')) return Promise.resolve({ statusCode: 401, data: { error: 'session_expired' }, header: {} })
      if (options.url.endsWith('/v1/session/refresh')) return refreshResponse
      return Promise.resolve({ statusCode: 200, data: { status: 'logged_out' }, header: {} })
    })

    const pending = api.requestJson('/api/v1/cars')
    for (let index = 0; index < 8 && mocks.request.mock.calls.length < 2; index += 1) await Promise.resolve()
    await api.matelinkApi.logout()
    resolveRefresh({ statusCode: 200, data: { access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 900 }, header: {} })

    await expect(pending).rejects.toMatchObject({ code: 'session_changed' })
    expect(session.readAppSession(storageAdapter)).toBeNull()
  })

  it('cancels a pending Tesla authorization before revoking the session', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'access', refreshToken: 'refresh', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-1', clientProof: 'proof-1', apiOrigin: 'https://api.example.test', expiresAt: null })
    mocks.request
      .mockResolvedValueOnce({ statusCode: 200, data: { status: 'cancelled' }, header: {} })
      .mockResolvedValueOnce({ statusCode: 200, data: { status: 'logged_out' }, header: {} })
    await api.matelinkApi.logout()
    expect(mocks.request.mock.calls[0][0].url).toContain('/v1/auth/miniprogram/cancel')
    expect(mocks.request.mock.calls[1][0].url).toContain('/v1/session/logout')
    expect(session.readPendingTeslaAuthorization(storageAdapter)).toBeNull()
  })

  it('rejects a server missing the Mini transaction contract without falling back to wx.login', async () => {
    const { api, session } = await modules()
    mocks.request.mockResolvedValue({ statusCode: 200, data: { authorization_url: 'https://auth.tesla.cn/authorize', transaction_id: 'app-txn' }, header: {} })
    await expect(api.matelinkApi.startTeslaAuthorization({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).rejects.toMatchObject({ code: 'invalid_authorization_response' })
    expect(mocks.login).not.toHaveBeenCalled()
    expect(session.readPendingTeslaAuthorization(storageAdapter)).toBeNull()
  })

  it('fails with a typed configuration error before transport when the base URL is absent', async () => {
    vi.resetModules()
    vi.stubEnv('TARO_APP_API_BASE_URL', '')
    const { matelinkApi, ApiConfigurationError } = await import('./api')
    await expect(matelinkApi.startTeslaAuthorization({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).rejects.toBeInstanceOf(ApiConfigurationError)
    expect(mocks.login).not.toHaveBeenCalled()
  })

  it('rejects an authenticated response without a canonical user id', async () => {
    const { api, session } = await modules()
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn', clientProof: 'proof', apiOrigin: 'https://api.example.test', expiresAt: null })
    mocks.request.mockResolvedValue({ statusCode: 200, data: { access_token: 'access', refresh_token: 'refresh' }, header: {} })
    await expect(api.matelinkApi.claimTeslaAuthorization()).rejects.toMatchObject({ code: 'invalid_session_response' })
    expect(session.readAppSession(storageAdapter)).toBeNull()
  })

  it('rejects arbitrary HTTPS pages as authorization entries', async () => {
    const { api } = await modules()
    expect(api.isTrustedAuthorizationURL('https://api.example.test/oauth/wechat/authorize')).toBe(false)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn/oauth2/v3/authorize')).toBe(true)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn/user/revoke/consent?revoke_client_id=client')).toBe(true)
    expect(api.isTrustedAuthorizationURL('https://evil.example.test/login')).toBe(false)
    expect(api.isTrustedAuthorizationURL('http://auth.tesla.cn/login')).toBe(false)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn/oauth2//v3/authorize')).toBe(false)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn:')).toBe(false)
  })

  it('deletes only the owning account session and accepts only a Tesla revoke URL', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'access', refreshToken: 'refresh', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    const { historyCacheKey } = await import('./history_cache')
    const accountCache = historyCacheKey('drives', 'user-a', 'vehicle-a', 'https://api.example.test')
    const otherCache = historyCacheKey('drives', 'user-b', 'vehicle-a', 'https://api.example.test')
    const ambiguousLegacy = 'matelink.history.v3.https%3A%2F%2Fapi.example.test.user-a.vehicle-a.drives'
    mocks.storage.set(ambiguousLegacy, { items: ['preserve-unverified-namespace'] })
    mocks.storage.set(accountCache, { items: [] })
    mocks.storage.set(otherCache, { items: [] })
    mocks.request.mockResolvedValue({
      statusCode: 200,
      data: { status: 'deleted', tesla_consent_revoke_url: 'https://auth.tesla.cn/user/revoke/consent?revoke_client_id=client' },
      header: {},
    })

    await expect(api.matelinkApi.deleteAccount()).resolves.toEqual({
      teslaConsentRevokeUrl: 'https://auth.tesla.cn/user/revoke/consent?revoke_client_id=client',
    })
    expect(mocks.request.mock.calls[0][0]).toMatchObject({
      method: 'DELETE',
      url: 'https://api.example.test/v1/account',
      header: expect.objectContaining({ Authorization: 'Bearer access' }),
    })
    expect(session.readAppSession(storageAdapter)).toBeNull()
    expect(mocks.storage.has(accountCache)).toBe(false)
    expect(mocks.storage.has(otherCache)).toBe(true)
    expect(mocks.storage.has(ambiguousLegacy)).toBe(true)
  })

  it('clears a deleted account without exposing an untrusted revoke URL', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'access', refreshToken: null, expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    mocks.request.mockResolvedValue({
      statusCode: 200,
      data: { status: 'deleted', tesla_consent_revoke_url: 'https://evil.example.test/revoke' },
      header: {},
    })

    await expect(api.matelinkApi.deleteAccount()).resolves.toEqual({ teslaConsentRevokeUrl: null })
    expect(session.readAppSession(storageAdapter)).toBeNull()
  })

  it('rejects a delayed claim after logout instead of restoring the cleared session', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'access-a', refreshToken: 'refresh-a', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-a', clientProof: 'proof-a', apiOrigin: 'https://api.example.test', expiresAt: null })
    let resolveClaim!: (value: unknown) => void
    const delayedClaim = new Promise(resolve => { resolveClaim = resolve })
    mocks.request.mockImplementation((options: { url: string }) => {
      if (options.url.endsWith('/v1/auth/miniprogram/claim')) return delayedClaim
      return Promise.resolve({ statusCode: 200, data: { status: 'ok' }, header: {} })
    })

    const claim = api.matelinkApi.claimTeslaAuthorization()
    for (let index = 0; index < 8 && mocks.request.mock.calls.length < 1; index += 1) await Promise.resolve()
    await api.matelinkApi.logout()
    resolveClaim({ statusCode: 200, data: { access_token: 'resurrected', refresh_token: 'resurrected-refresh', user: { id: 'user-a' } }, header: {} })

    await expect(claim).rejects.toMatchObject({ code: 'session_changed' })
    expect(session.readAppSession(storageAdapter)).toBeNull()
  })

  it('keeps a replacement Tesla session and captured logout token across a delayed cancel', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'access-a', refreshToken: 'refresh-a', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-a', clientProof: 'proof-a', apiOrigin: 'https://api.example.test', expiresAt: null })
    let resolveCancel!: (value: unknown) => void
    const delayedCancel = new Promise(resolve => { resolveCancel = resolve })
    mocks.login.mockResolvedValue({ code: 'wechat-code' })
    mocks.request.mockImplementation((options: { url: string }) => {
      if (options.url.endsWith('/v1/auth/miniprogram/cancel')) return delayedCancel
      if (options.url.endsWith('/v1/auth/exchange')) return Promise.resolve({ statusCode: 200, data: { access_token: 'access-b', refresh_token: 'refresh-b', user: { id: 'user-b' } }, header: {} })
      if (options.url.endsWith('/v1/session/logout')) return Promise.resolve({ statusCode: 200, data: { status: 'logged_out' }, header: {} })
      return Promise.resolve({ statusCode: 200, data: {}, header: {} })
    })

    const logout = api.matelinkApi.logout()
    for (let index = 0; index < 8 && !mocks.request.mock.calls.some(call => String(call[0]?.url ?? '').endsWith('/v1/auth/miniprogram/cancel')); index += 1) await Promise.resolve()
    await expect(api.matelinkApi.exchangeTeslaTicket('new-one-use-ticket')).resolves.toMatchObject({ userId: 'user-b' })
    resolveCancel({ statusCode: 200, data: { status: 'cancelled' }, header: {} })
    await logout

    expect(session.readAppSession(storageAdapter)?.userId).toBe('user-b')
    const logoutCall = mocks.request.mock.calls.find(call => String(call[0]?.url ?? '').endsWith('/v1/session/logout'))
    expect(logoutCall?.[0].header.Authorization).toBe('Bearer access-a')
  })

  it('constructs history URLs and validates authorization without URL globals', async () => {
    const { api } = await modules()
    const runtime = globalThis as unknown as { URL?: unknown; URLSearchParams?: unknown }
    const originalURL = runtime.URL
    const original = runtime.URLSearchParams
    runtime.URL = undefined
    runtime.URLSearchParams = undefined
    try {
      mocks.request.mockResolvedValue({ statusCode: 200, data: { data: { drives: [], meta: { page: 1, show: 20, total: 0, total_pages: 0 } } }, header: {} })
      await api.matelinkApi.getDrives(11, 1, 20)
      expect(mocks.request.mock.calls[0][0].url).toContain('/api/v1/cars/11/drives?page=1&show=20')
      expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn/oauth2/v3/authorize')).toBe(true)
    } finally {
      runtime.URL = originalURL
      runtime.URLSearchParams = original
    }
  })

  it('checks and claims a ready Tesla authorization using the stored proof', async () => {
    const { api, session } = await modules()
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-1', clientProof: 'proof-1', apiOrigin: 'https://api.example.test', expiresAt: null })
    mocks.request
      .mockResolvedValueOnce({ statusCode: 200, data: { status: 'ready', expires_at: '2026-09-13T01:00:00Z' }, header: {} })
      .mockResolvedValueOnce({ statusCode: 200, data: { access_token: 'access', refresh_token: 'refresh', expires_in: 900, user: { id: 'user-a' } }, header: {} })
    await expect(api.matelinkApi.getTeslaAuthorizationStatus()).resolves.toEqual({ status: 'ready', expiresAt: '2026-09-13T01:00:00Z' })
    await expect(api.matelinkApi.claimTeslaAuthorization()).resolves.toMatchObject({ accessToken: 'access', userId: 'user-a' })
    expect(mocks.request.mock.calls[1][0].data).toEqual({ transaction_id: 'txn-1', client_proof: 'proof-1' })
    expect(session.readPendingTeslaAuthorization(storageAdapter)).toBeNull()
  })

  it('does not use a numeric car id as a cross-account stable identity', async () => {
    const { api } = await modules()
    mocks.request.mockResolvedValue({
      statusCode: 200,
      data: { data: { cars: [{ car_id: 11, name: 'Unstable vehicle' }] } },
      header: {},
    })
    await expect(api.matelinkApi.getCars()).resolves.toEqual([])
  })

  it('exposes telemetry configure as an authenticated POST operation', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, { apiOrigin: 'https://api.example.test',
      accessToken: 'access', refreshToken: 'refresh', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    mocks.request.mockResolvedValue({ statusCode: 200, data: { data: { status: 'waiting_vehicle' } }, header: {} })
    await expect(api.matelinkApi.configureTelemetry(11)).resolves.toEqual({ status: 'waiting_vehicle' })
    expect(mocks.request.mock.calls[0][0]).toMatchObject({ method: 'POST', data: {}, url: 'https://api.example.test/api/v1/cars/11/telemetry/configure' })
  })

  it('normalizes vehicle metadata and nested status evidence without dropping false or zero', async () => {
    const { api } = await modules()
    mocks.request.mockResolvedValue({
      statusCode: 200,
      data: {
        data: {
          status: {
            state: 'online',
            model: '3',
            trim_badging: 'Long Range',
            exterior_color: 'Pearl White',
            wheel_type: 'Aero',
            odometer: 0,
            car_status: { locked: false, sentry_mode: true, windows_open: false, doors_open: false, trunk_open: false, frunk_open: false },
            battery_details: { battery_level: 0, rated_battery_range: 0 },
            car_geodata: { latitude: 31.2, longitude: 121.4 },
            driving_details: { shift_state: 'P', speed: 0, power: 0, heading: 0 },
            climate_details: { is_climate_on: false, inside_temp: 0, outside_temp: 0 },
            charging_details: { plugged_in: false, charging_state: 'Disconnected', charge_energy_added: 0, charger_power: 0 },
          },
          observed_at: '2026-09-13T09:00:00Z',
          source: 'telemetry_mqtt',
        },
      },
      header: {},
    })
    await expect(api.matelinkApi.getCarStatus(11)).resolves.toEqual({
      state: 'online', model: '3', trim: 'Long Range', exteriorColor: 'Pearl White', wheelType: 'Aero',
      batteryLevel: 0, ratedRange: 0, odometer: 0, location: { latitude: 31.2, longitude: 121.4 },
      locked: false, sentryMode: true, windowsOpen: false, doorsOpen: false, trunkOpen: false, frunkOpen: false,
      isClimateOn: false, insideTemp: 0, outsideTemp: 0, pluggedIn: false, speed: 0, power: 0, shiftState: 'P', heading: 0,
      chargingState: 'Disconnected', chargeEnergyAdded: 0, chargerPower: 0,
      observedAt: '2026-09-13T09:00:00Z', source: 'telemetry_mqtt',
      idealRange: null, estimatedRange: null, chargerVoltage: null, chargerActualCurrent: null, timeToFullChargeHours: null,
      chargeLimitSoc: null, chargerPhases: null, chargeCurrentRequest: null, chargeCurrentRequestMax: null, chargePortDoorOpen: null,
      isPreconditioning: null, geofence: null, fieldSources: {},
    })
  })
})
