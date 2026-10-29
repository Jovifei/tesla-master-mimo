import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ request: vi.fn(), login: vi.fn(), values: new Map<string, unknown>(), failPreferenceWrite: false, failPreferenceRemove: false }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, login: platform.login,
  getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => { if (key.startsWith('matelink.wechat.recovery.v1.') && platform.failPreferenceWrite) throw Error('storage failed'); platform.values.set(key, value) },
  removeStorageSync: (key: string) => { if (key.startsWith('matelink.wechat.recovery.v1.') && platform.failPreferenceRemove) throw Error('storage failed'); platform.values.delete(key) },
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
} }))

const origin = 'https://api.example.test'
const session = (userId = 'a') => ({ apiOrigin: origin, accessToken: `access-${userId}`, refreshToken: `refresh-${userId}`, expiresAt: null, userId, linkRequired: false })
const response = (statusCode: number, data: unknown) => ({ statusCode, data, header: {} })
async function modules() { return { api: await import('./api'), session: await import('./session') } }
const store = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, value), removeStorageSync: (key: string) => platform.values.delete(key) }

beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', origin); vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'false')
  platform.values.clear(); platform.request.mockReset(); platform.login.mockReset(); platform.failPreferenceWrite = false; platform.failPreferenceRemove = false
})
afterEach(() => vi.unstubAllEnvs())

it('binds only after an explicit call and stores no WeChat code', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.login.mockResolvedValue({ code: 'synthetic-code' })
  platform.request.mockResolvedValueOnce(response(200, { status: 'bound' }))
  await api.matelinkApi.bindWechat('4')
  expect(platform.request).toHaveBeenCalledWith(expect.objectContaining({
    url: `${origin}/v1/auth/wechat/bind`, method: 'POST', data: { code: 'synthetic-code', binding_version: '4' },
    header: expect.objectContaining({ Authorization: 'Bearer access-a' }),
  }))
  expect(storage.readWechatRecoveryPreference(store, origin)).toEqual({ apiOrigin: origin, boundUserId: 'a', enabled: true })
  expect(JSON.stringify([...platform.values])).not.toContain('synthetic-code')
})

it('never retries a consumed code when Tesla reauthorization is required', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.login.mockResolvedValue({ code: 'synthetic-code' })
  platform.request.mockResolvedValueOnce(response(401, { error: 'tesla_reauthorization_required' }))
  await expect(api.matelinkApi.bindWechat('0')).rejects.toMatchObject({ code: 'tesla_reauthorization_required' })
  expect(platform.login).toHaveBeenCalledTimes(1)
  expect(platform.request).toHaveBeenCalledTimes(1)
})

it('shares simultaneous automatic restores and records the resulting session source', async () => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'restore-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const first = api.ensureWechatSession(), second = api.ensureWechatSession()
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(1))
  release(response(200, { access_token: 'restored-access', refresh_token: 'restored-refresh', user: { id: 'a' } }))
  await Promise.all([first, second])
  expect(platform.login).toHaveBeenCalledTimes(1)
  expect(storage.readAppSession(store)).toMatchObject({ userId: 'a', source: 'wechat' })
  expect(JSON.stringify([...platform.values])).not.toContain('restore-code')
})

it('keeps an explicit logout after a delayed automatic restore and a new launch', async () => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'restore-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const restoring = api.ensureWechatSession()
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(1))
  await api.matelinkApi.logout()
  release(response(200, { access_token: 'late-access', user: { id: 'a' } }))
  await restoring
  expect(storage.readAppSession(store)).toBeNull()
  expect(storage.readWechatRecoveryPreference(store, origin)?.enabled).toBe(false)
  vi.resetModules()
  const restarted = await import('./api')
  await restarted.ensureWechatSession()
  expect(platform.login).toHaveBeenCalledTimes(1)
})

it('removes an old recovery preference if it cannot persist the disabled setting', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.failPreferenceWrite = true
  platform.request.mockResolvedValue(response(200, { status: 'logged_out' }))
  await api.matelinkApi.logout()
  expect(storage.readWechatRecoveryPreference(store, origin)).toBeNull()
  vi.resetModules()
  await (await import('./api')).ensureWechatSession()
  expect(platform.login).not.toHaveBeenCalled()
})

it('reports when logout cannot persist or remove its disabled recovery preference', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.failPreferenceWrite = true; platform.failPreferenceRemove = true
  platform.request.mockResolvedValue(response(200, { status: 'logged_out' }))
  await expect(api.matelinkApi.logout()).rejects.toMatchObject({ code: 'wechat_recovery_storage_failed' })
  expect(storage.readAppSession(store)).toBeNull()
  await api.ensureWechatSession()
  expect(platform.login).not.toHaveBeenCalled()
})

it('reports the disabled-recovery storage failure even when remote logout also fails', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.failPreferenceWrite = true; platform.failPreferenceRemove = true
  platform.request.mockRejectedValue(Error('PRIVATE_TRANSPORT_DIAGNOSTIC'))
  const failure = await api.matelinkApi.logout().catch(reason => reason)
  expect(failure).toMatchObject({ code: 'wechat_recovery_storage_failed' })
  expect(failure.message).toContain('重启后')
  expect(failure.message).not.toContain('PRIVATE_TRANSPORT_DIAGNOSTIC')
  expect(storage.readAppSession(store)).toBeNull()
  await api.ensureWechatSession()
  expect(platform.login).not.toHaveBeenCalled()
})

it('reports a local recovery preference failure without hiding a successful account deletion', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.failPreferenceWrite = true; platform.failPreferenceRemove = true
  platform.request.mockResolvedValue(response(200, { status: 'deleted' }))
  await expect(api.matelinkApi.deleteAccount()).resolves.toMatchObject({ teslaConsentRevokeUrl: null, recoveryStorageWarning: true })
  expect(storage.readAppSession(store)).toBeNull()
})

it('clears a WeChat-origin session on unbind but preserves a Tesla-origin session', async () => {
  const { api, session: storage } = await modules()
  platform.request.mockResolvedValue(response(200, { status: 'unbound' }))
  storage.writeAppSession(store, session())
  await api.matelinkApi.unbindWechat()
  expect(storage.readAppSession(store)?.userId).toBe('a')
  storage.writeAppSession(store, { ...session(), source: 'wechat' })
  await api.matelinkApi.unbindWechat()
  expect(storage.readAppSession(store)).toBeNull()
})

it('refreshes a rejected bearer and obtains a different code before one bind retry', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.login.mockResolvedValueOnce({ code: 'first-code' }).mockResolvedValueOnce({ code: 'fresh-code' })
  platform.request.mockResolvedValueOnce(response(401, { error: 'session_required' }))
    .mockResolvedValueOnce(response(200, { access_token: 'rotated', refresh_token: 'rotated-refresh' }))
    .mockResolvedValueOnce(response(200, { bound: false, binding_version: '2' }))
    .mockResolvedValueOnce(response(200, { status: 'bound' }))
  await api.matelinkApi.bindWechat('2')
  expect(platform.request.mock.calls.map(([request]) => request.url)).toEqual([
    `${origin}/v1/auth/wechat/bind`, `${origin}/v1/session/refresh`, `${origin}/v1/auth/wechat/binding`, `${origin}/v1/auth/wechat/bind`,
  ])
  expect(platform.request.mock.calls[3][0]).toMatchObject({ data: { code: 'fresh-code', binding_version: '2' }, header: { Authorization: 'Bearer rotated' } })
  expect(storage.readWechatRecoveryPreference(store, origin)?.enabled).toBe(true)
})

it('does not cross an intervening unbind when retrying a bind after refresh', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.login.mockResolvedValue({ code: 'first-code' })
  platform.request.mockResolvedValueOnce(response(401, { error: 'session_required' }))
    .mockResolvedValueOnce(response(200, { access_token: 'rotated', refresh_token: 'next' }))
    .mockResolvedValueOnce(response(200, { bound: false, binding_version: '3' }))
  await expect(api.matelinkApi.bindWechat('2')).rejects.toMatchObject({ code: 'wechat_bind_expired' })
  expect(platform.login).toHaveBeenCalledTimes(1)
  expect(platform.request).toHaveBeenCalledTimes(3)
})

it('checking whether the account is bound does not enable this device', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.request.mockResolvedValue(response(200, { bound: true, binding_version: '7' }))
  await expect(api.matelinkApi.getWechatBinding()).resolves.toEqual({ bound: true, bindingVersion: '7' })
  expect(storage.readWechatRecoveryPreference(store, origin)).toBeNull()
  expect(platform.login).not.toHaveBeenCalled()
})

it.each([undefined, '', '1.5', '-1', 7])('rejects an invalid binding version %s before obtaining a code', async version => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.request.mockResolvedValue(response(200, { bound: false, binding_version: version }))
  await expect(api.matelinkApi.prepareWechatBinding()).rejects.toMatchObject({ code: 'invalid_api_response' })
  expect(platform.login).not.toHaveBeenCalled()
})

it('does not rotate a session from a background binding-status read during Tesla authorization', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.request.mockResolvedValue(response(401, { error: 'session_required' }))
  await expect(api.matelinkApi.getWechatBinding()).rejects.toMatchObject({ code: 'session_required' })
  expect(platform.request).toHaveBeenCalledTimes(1)
  expect(storage.readAppSession(store)?.accessToken).toBe('access-a')
})

it('refreshes an expired session and restores it with WeChat if refresh is rejected', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, { ...session(), expiresAt: '2000-01-01T00:00:00Z' })
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.request.mockResolvedValueOnce(response(401, { error: 'session_required' }))
    .mockResolvedValueOnce(response(200, { access_token: 'restored', refresh_token: 'new-refresh', user: { id: 'a' } }))
  await api.ensureWechatSession()
  expect(platform.request.mock.calls.map(([request]) => request.url)).toEqual([`${origin}/v1/session/refresh`, `${origin}/v1/auth/wechat/restore`])
  expect(storage.readAppSession(store)).toMatchObject({ accessToken: 'restored', userId: 'a', source: 'wechat' })
})

it('notifies the visible page once after publishing the recovery flight', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, { ...session(), expiresAt: '2000-01-01T00:00:00Z' })
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  let release!: (value: unknown) => void
  platform.request.mockResolvedValueOnce(response(401, { error: 'session_required' }))
    .mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const joining: Promise<void>[] = []
  const unsubscribe = api.subscribeSessionRecovery(() => { joining.push(api.ensureWechatSession()) })
  const first = api.ensureWechatSession()
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(2))
  expect(joining).toHaveLength(1)
  let finished = false
  void joining[0].then(() => { finished = true })
  await Promise.resolve(); await Promise.resolve()
  expect(finished).toBe(false)
  release(response(200, { access_token: 'restored', user: { id: 'a' } }))
  await Promise.all([first, ...joining])
  expect(platform.login).toHaveBeenCalledTimes(1)
  unsubscribe()
})

it('notifies once and stops loading when expired-session recovery is disabled', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, { ...session(), expiresAt: '2000-01-01T00:00:00Z' })
  platform.request.mockResolvedValue(response(401, { error: 'session_required' }))
  const joining: Promise<void>[] = []
  const unsubscribe = api.subscribeSessionRecovery(() => { joining.push(api.ensureWechatSession()) })
  await api.ensureWechatSession()
  await Promise.all(joining)
  expect(joining).toHaveLength(1)
  expect(storage.readAppSession(store)).toBeNull()
  expect(platform.login).not.toHaveBeenCalled()
  unsubscribe()
})

it('a second page waits for restore started after an expired session was cleared', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, { ...session(), expiresAt: '2000-01-01T00:00:00Z' })
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  let release!: (value: unknown) => void
  platform.request.mockResolvedValueOnce(response(401, { error: 'session_required' }))
    .mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const first = api.ensureWechatSession()
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(2))
  let secondFinished = false
  const second = api.ensureWechatSession().then(() => { secondFinished = true })
  await Promise.resolve(); await Promise.resolve()
  expect(secondFinished).toBe(false)
  release(response(200, { access_token: 'restored', user: { id: 'a' } }))
  await Promise.all([first, second])
  expect(storage.readAppSession(store)?.userId).toBe('a')
})

it('a page waits for a manual restore even when automatic recovery was disabled', async () => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: false })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const manual = api.matelinkApi.restoreWechatSession()
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(1))
  let pageFinished = false
  const page = api.ensureWechatSession().then(() => { pageFinished = true })
  await Promise.resolve(); await Promise.resolve()
  expect(pageFinished).toBe(false)
  release(response(200, { access_token: 'restored', user: { id: 'a' } }))
  await Promise.all([manual, page])
  expect(storage.readAppSession(store)?.userId).toBe('a')
})

it('retains the existing account and cache access when expired-session refresh is offline', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, { ...session(), expiresAt: '2000-01-01T00:00:00Z' })
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.request.mockRejectedValue(Error('offline'))
  await api.ensureWechatSession()
  expect(storage.readAppSession(store)?.userId).toBe('a')
  expect(platform.login).not.toHaveBeenCalled()
})

it('does not restore a different account through an old automatic preference', async () => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.request.mockResolvedValue(response(200, { access_token: 'other-access', user: { id: 'b' } }))
  await api.ensureWechatSession()
  expect(storage.readAppSession(store)).toBeNull()
  expect(api.getWechatRecoveryFailure()?.code).toBe('wechat_account_changed')
})

it.each([
  [404, 'wechat_not_bound', false],
  [401, 'tesla_reauthorization_required', true],
  [503, 'wechat_not_configured', true],
  [503, 'wechat_provider_unavailable', true],
])('reports automatic restore error %s %s and preserves only appropriate preferences', async (status, code, enabled) => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.request.mockResolvedValue(response(status, { error: code }))
  await api.ensureWechatSession()
  expect(api.getWechatRecoveryFailure()?.code).toBe(code)
  expect(storage.readWechatRecoveryPreference(store, origin)?.enabled).toBe(enabled)
  expect(storage.readAppSession(store)).toBeNull()
})

it('lets a user manually enter after logout and explicitly enables later recovery', async () => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: false })
  platform.login.mockResolvedValue({ code: 'manual-code' })
  platform.request.mockResolvedValue(response(200, { access_token: 'manual', user: { id: 'a' } }))
  await api.matelinkApi.restoreWechatSession()
  expect(storage.readAppSession(store)).toMatchObject({ userId: 'a', source: 'wechat' })
  expect(storage.readWechatRecoveryPreference(store, origin)?.enabled).toBe(true)
})

it('rejects automatic recovery while Tesla authorization is starting', async () => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const starting = api.matelinkApi.startTeslaAuthorization({ termsVersion: api.JOURVOLT_TERMS_VERSION, privacyVersion: api.JOURVOLT_PRIVACY_VERSION })
  await api.ensureWechatSession()
  expect(platform.login).not.toHaveBeenCalled()
  release(response(200, { authorization_url: 'https://auth.tesla.com/oauth2/v3/authorize?state=fixture', transaction_id: 'txn', client_proof: 'proof' }))
  await starting
})

it('waits for an already running refresh before it starts Tesla authorization', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/v1/session/refresh')) return new Promise(resolve => { release = resolve })
    if (url.endsWith('/v1/auth/tesla/start')) return Promise.resolve(response(200, { authorization_url: 'https://auth.tesla.com/oauth2/v3/authorize?state=fixture', transaction_id: 'txn', client_proof: 'proof' }))
    throw Error('unexpected route')
  })
  const refreshing = api.refreshSession()
  const starting = api.matelinkApi.startTeslaAuthorization({ termsVersion: api.JOURVOLT_TERMS_VERSION, privacyVersion: api.JOURVOLT_PRIVACY_VERSION })
  expect(platform.request).toHaveBeenCalledTimes(1)
  release(response(200, { access_token: 'rotated', refresh_token: 'next' }))
  await refreshing; await starting
  expect(storage.readAppSession(store)?.accessToken).toBe('rotated')
  expect(storage.readPendingTeslaAuthorization(store)?.transactionId).toBe('txn')
})

it('an old failed refresh cannot clear a newly started Tesla authorization', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  let rejectRefresh!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/api/v1/cars')) return Promise.resolve(response(401, { error: 'session_required' }))
    if (url.endsWith('/v1/session/refresh')) return new Promise(resolve => { rejectRefresh = resolve })
    if (url.endsWith('/v1/auth/tesla/start')) return Promise.resolve(response(200, { authorization_url: 'https://auth.tesla.com/oauth2/v3/authorize?state=fixture', transaction_id: 'new-txn', client_proof: 'new-proof' }))
    throw Error('unexpected route')
  })
  const oldRequest = api.matelinkApi.getCars()
  void oldRequest.catch(() => {})
  await vi.waitFor(() => expect(platform.request.mock.calls.some(([request]) => request.url.endsWith('/v1/session/refresh'))).toBe(true))
  const starting = api.matelinkApi.startTeslaAuthorization({ termsVersion: api.JOURVOLT_TERMS_VERSION, privacyVersion: api.JOURVOLT_PRIVACY_VERSION })
  rejectRefresh(response(401, { error: 'session_required' }))
  await starting
  await expect(oldRequest).rejects.toMatchObject({ code: 'session_changed' })
  expect(storage.readPendingTeslaAuthorization(store)).toMatchObject({ transactionId: 'new-txn', clientProof: 'new-proof' })
  expect(storage.readAppSession(store)?.userId).toBe('a')
})

it('does not clear a Tesla authorization that was already pending when a vehicle request began', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  storage.writePendingTeslaAuthorization(store, { transactionId: 'txn', clientProof: 'proof', apiOrigin: origin, expiresAt: null })
  platform.request.mockResolvedValue(response(401, { error: 'session_required' }))
  await expect(api.matelinkApi.getCars()).rejects.toMatchObject({ status: 401 })
  expect(storage.readPendingTeslaAuthorization(store)?.transactionId).toBe('txn')
  expect(storage.readAppSession(store)?.userId).toBe('a')
})

it('does not bind the previous account while Tesla authorization is pending or begins during login', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  const pending = { transactionId: 'txn', clientProof: 'proof', apiOrigin: origin, expiresAt: null }
  storage.writePendingTeslaAuthorization(store, pending)
  await expect(api.matelinkApi.bindWechat('0')).rejects.toMatchObject({ code: 'session_changed' })
  expect(platform.login).not.toHaveBeenCalled()
  storage.clearPendingTeslaAuthorization(store)
  let release!: (value: unknown) => void
  platform.request.mockResolvedValue(response(200, { bound: false, binding_version: '0' }))
  platform.login.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const binding = api.matelinkApi.bindWechat('0')
  await vi.waitFor(() => expect(platform.login).toHaveBeenCalledTimes(1))
  storage.writePendingTeslaAuthorization(store, pending)
  release({ code: 'stale-code' })
  await expect(binding).rejects.toMatchObject({ code: 'session_changed' })
  expect(platform.request).not.toHaveBeenCalled()
})

it('does not allow a delayed restore to replace a Tesla ticket exchange', async () => {
  const { api, session: storage } = await modules()
  storage.writeWechatRecoveryPreference(store, { apiOrigin: origin, boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    .mockResolvedValueOnce(response(200, { access_token: 'tesla-b', user: { id: 'b' } }))
  const restoring = api.ensureWechatSession()
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(1))
  await api.matelinkApi.exchangeTeslaTicket('synthetic-ticket')
  release(response(200, { access_token: 'old-a', user: { id: 'a' } }))
  await restoring
  expect(storage.readAppSession(store)).toMatchObject({ userId: 'b', accessToken: 'tesla-b' })
  expect(storage.readWechatRecoveryPreference(store, origin)?.enabled).toBe(false)
})

it('retains the WeChat session source through token refresh before unbinding', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, { ...session(), source: 'wechat' })
  platform.request.mockResolvedValueOnce(response(200, { access_token: 'rotated', refresh_token: 'rotated-refresh' }))
    .mockResolvedValueOnce(response(200, { status: 'unbound' }))
  await api.refreshSession()
  expect(storage.readAppSession(store)?.source).toBe('wechat')
  await api.matelinkApi.unbindWechat()
  expect(storage.readAppSession(store)).toBeNull()
})

it('does not send an unbind while a delayed bind can still commit', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.login.mockResolvedValue({ code: 'bind-code' })
  let release!: () => void, bound = false
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/binding')) return Promise.resolve(response(200, { bound, binding_version: '0' }))
    if (url.endsWith('/bind')) return new Promise(resolve => { release = () => { bound = true; resolve(response(200, { status: 'bound' })) } })
    if (url.endsWith('/unbind')) { bound = false; return Promise.resolve(response(200, { status: 'unbound' })) }
    throw Error('unexpected route')
  })
  const binding = api.matelinkApi.bindWechat('0')
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(1))
  await expect(api.matelinkApi.unbindWechat()).rejects.toMatchObject({ code: 'session_changed' })
  expect(platform.request).toHaveBeenCalledTimes(1)
  release(); await binding
  await api.matelinkApi.unbindWechat()
  expect(bound).toBe(false)
})

it('surfaces an expired binding version without retrying the code', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  platform.request.mockResolvedValueOnce(response(409, { error: 'wechat_bind_expired' }))
  await expect(api.matelinkApi.bindWechat('2')).rejects.toMatchObject({ code: 'wechat_bind_expired', message: '绑定操作已取消，请重新确认' })
  expect(platform.login).toHaveBeenCalledTimes(1)
  expect(platform.request).toHaveBeenCalledTimes(1)
})

it('does not obtain a bind code while an unbind mutation is still in flight', async () => {
  const { api, session: storage } = await modules()
  storage.writeAppSession(store, session())
  let release!: (value: unknown) => void
  platform.request.mockImplementation(() => new Promise(resolve => { release = resolve }))
  const unbinding = api.matelinkApi.unbindWechat()
  await expect(api.matelinkApi.bindWechat('0')).rejects.toMatchObject({ code: 'session_changed' })
  expect(platform.login).not.toHaveBeenCalled()
  release(response(200, { status: 'unbound' }))
  await unbinding
})

it('never calls real identity APIs in fixture mode', async () => {
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'true')
  const { api } = await modules()
  await api.ensureWechatSession()
  await expect(api.matelinkApi.bindWechat('0')).rejects.toMatchObject({ code: 'wechat_mock_unavailable' })
  await expect(api.matelinkApi.unbindWechat()).rejects.toMatchObject({ code: 'wechat_mock_unavailable' })
  expect(platform.login).not.toHaveBeenCalled()
  expect(platform.request).not.toHaveBeenCalled()
})
