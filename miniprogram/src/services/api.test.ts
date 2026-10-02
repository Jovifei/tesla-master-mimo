import { beforeEach, describe, expect, it, vi } from 'vitest'

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
    session.writeAppSession(storageAdapter, {
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
    session.writeAppSession(storageAdapter, {
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
    session.writeAppSession(storageAdapter, {
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

  it('cancels a pending WeChat authorization before revoking the session', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, {
      accessToken: 'access', refreshToken: 'refresh', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-1', clientProof: 'proof-1', apiOrigin: 'https://api.example.test', expiresAt: null })
    mocks.request
      .mockResolvedValueOnce({ statusCode: 200, data: { status: 'cancelled' }, header: {} })
      .mockResolvedValueOnce({ statusCode: 200, data: { status: 'logged_out' }, header: {} })
    await api.matelinkApi.logout()
    expect(mocks.request.mock.calls[0][0].url).toContain('/v1/auth/wechat/cancel')
    expect(mocks.request.mock.calls[1][0].url).toContain('/v1/session/logout')
    expect(session.readPendingTeslaAuthorization(storageAdapter)).toBeNull()
  })

  it('exchanges wx.login and retains a link token until Tesla authorization starts', async () => {
    const { api, session } = await modules()
    mocks.login.mockResolvedValue({ code: 'wechat-code' })
    mocks.request
      .mockResolvedValueOnce({ statusCode: 200, data: { status: 'link_required', link_token: 'short-link', expires_at: '2026-09-13T00:15:00Z' }, header: {} })
      .mockResolvedValueOnce({ statusCode: 200, data: { authorization_url: 'https://auth.tesla.cn/authorize', web_authorization_url: 'https://api.example.test/oauth/wechat/authorize?state=s', transaction_id: 'txn', client_proof: 'proof', expires_at: null }, header: {} })

    await expect(api.matelinkApi.loginWithWechat({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).resolves.toEqual({
      status: 'link_required',
      expiresAt: '2026-09-13T00:15:00Z',
    })
    expect(session.readPendingWechatLink(storageAdapter)).toEqual({ linkToken: 'short-link', expiresAt: '2026-09-13T00:15:00Z' })
    await api.matelinkApi.startTeslaAuthorization({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })
    expect(mocks.request.mock.calls[1][0].header).toMatchObject({
      'X-WeChat-Link-Token': 'short-link',
      'X-WeChat-Channel': 'wechat',
      'X-JourVolt-Terms-Version': '2026-08-21',
      'X-JourVolt-Privacy-Version': '2026-08-21',
    })
    expect(session.readPendingTeslaAuthorization(storageAdapter)).toMatchObject({ transactionId: 'txn', clientProof: 'proof', apiOrigin: 'https://api.example.test' })
  })

  it('fails with a typed configuration error before wx.login when the base URL is absent', async () => {
    vi.resetModules()
    vi.stubEnv('TARO_APP_API_BASE_URL', '')
    const { matelinkApi, ApiConfigurationError } = await import('./api')
    await expect(matelinkApi.loginWithWechat({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).rejects.toBeInstanceOf(ApiConfigurationError)
    expect(mocks.login).not.toHaveBeenCalled()
  })

  it('rejects an authenticated response without a canonical user id', async () => {
    const { api, session } = await modules()
    mocks.login.mockResolvedValue({ code: 'wechat-code' })
    mocks.request.mockResolvedValue({ statusCode: 200, data: { access_token: 'access', refresh_token: 'refresh' }, header: {} })
    await expect(api.matelinkApi.loginWithWechat({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).rejects.toMatchObject({ code: 'invalid_session_response' })
    expect(session.readAppSession(storageAdapter)).toBeNull()
  })

  it('rejects arbitrary HTTPS pages as authorization entries', async () => {
    const { api } = await modules()
    expect(api.isTrustedAuthorizationURL('https://api.example.test/oauth/wechat/authorize')).toBe(true)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn/oauth2/v3/authorize')).toBe(true)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn/user/revoke/consent?revoke_client_id=client')).toBe(true)
    expect(api.isTrustedAuthorizationURL('https://evil.example.test/login')).toBe(false)
    expect(api.isTrustedAuthorizationURL('http://auth.tesla.cn/login')).toBe(false)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn/oauth2//v3/authorize')).toBe(false)
    expect(api.isTrustedAuthorizationURL('https://auth.tesla.cn:')).toBe(false)
  })

  it('deletes only the owning account session and accepts only a Tesla revoke URL', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, {
      accessToken: 'access', refreshToken: 'refresh', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    const accountCache = 'matelink.history.v3.https%3A%2F%2Fapi.example.test.user-a.vehicle-a.drives'
    const otherCache = 'matelink.history.v3.https%3A%2F%2Fapi.example.test.user-b.vehicle-a.drives'
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
  })

  it('clears a deleted account without exposing an untrusted revoke URL', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, {
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
    session.writeAppSession(storageAdapter, {
      accessToken: 'access-a', refreshToken: 'refresh-a', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-a', clientProof: 'proof-a', apiOrigin: 'https://api.example.test', expiresAt: null })
    let resolveClaim!: (value: unknown) => void
    const delayedClaim = new Promise(resolve => { resolveClaim = resolve })
    mocks.request.mockImplementation((options: { url: string }) => {
      if (options.url.endsWith('/v1/auth/wechat/claim')) return delayedClaim
      return Promise.resolve({ statusCode: 200, data: { status: 'ok' }, header: {} })
    })

    const claim = api.matelinkApi.claimWechatAuthorization()
    for (let index = 0; index < 8 && mocks.request.mock.calls.length < 1; index += 1) await Promise.resolve()
    await api.matelinkApi.logout()
    resolveClaim({ statusCode: 200, data: { access_token: 'resurrected', refresh_token: 'resurrected-refresh', user: { id: 'user-a' } }, header: {} })

    await expect(claim).rejects.toMatchObject({ code: 'session_changed' })
    expect(session.readAppSession(storageAdapter)).toBeNull()
  })

  it('keeps a replacement WeChat session and captured logout token across a delayed cancel', async () => {
    const { api, session } = await modules()
    session.writeAppSession(storageAdapter, {
      accessToken: 'access-a', refreshToken: 'refresh-a', expiresAt: null, userId: 'user-a', linkRequired: false,
    })
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-a', clientProof: 'proof-a', apiOrigin: 'https://api.example.test', expiresAt: null })
    let resolveCancel!: (value: unknown) => void
    const delayedCancel = new Promise(resolve => { resolveCancel = resolve })
    mocks.login.mockResolvedValue({ code: 'wechat-code' })
    mocks.request.mockImplementation((options: { url: string }) => {
      if (options.url.endsWith('/v1/auth/wechat/cancel')) return delayedCancel
      if (options.url.endsWith('/v1/auth/wechat/session')) return Promise.resolve({ statusCode: 200, data: { access_token: 'access-b', refresh_token: 'refresh-b', user: { id: 'user-b' } }, header: {} })
      if (options.url.endsWith('/v1/session/logout')) return Promise.resolve({ statusCode: 200, data: { status: 'logged_out' }, header: {} })
      return Promise.resolve({ statusCode: 200, data: {}, header: {} })
    })

    const logout = api.matelinkApi.logout()
    for (let index = 0; index < 8 && !mocks.request.mock.calls.some(call => String(call[0]?.url ?? '').endsWith('/v1/auth/wechat/cancel')); index += 1) await Promise.resolve()
    await expect(api.matelinkApi.loginWithWechat({ termsVersion: '2026-08-21', privacyVersion: '2026-08-21' })).resolves.toMatchObject({ status: 'authenticated' })
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

  it('checks and claims a ready WeChat authorization using the stored proof', async () => {
    const { api, session } = await modules()
    session.writePendingTeslaAuthorization(storageAdapter, { transactionId: 'txn-1', clientProof: 'proof-1', apiOrigin: 'https://api.example.test', expiresAt: null })
    mocks.request
      .mockResolvedValueOnce({ statusCode: 200, data: { status: 'ready', expires_at: '2026-09-13T01:00:00Z' }, header: {} })
      .mockResolvedValueOnce({ statusCode: 200, data: { access_token: 'access', refresh_token: 'refresh', expires_in: 900, user: { id: 'user-a' } }, header: {} })
    await expect(api.matelinkApi.getWechatAuthorizationStatus()).resolves.toEqual({ status: 'ready', expiresAt: '2026-09-13T01:00:00Z' })
    await expect(api.matelinkApi.claimWechatAuthorization('callback-ref')).resolves.toMatchObject({ accessToken: 'access', userId: 'user-a' })
    expect(mocks.request.mock.calls[1][0].data).toEqual({ transaction_id: 'txn-1', client_proof: 'proof-1', callback_ref: 'callback-ref' })
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
    session.writeAppSession(storageAdapter, {
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
    })
  })
})
