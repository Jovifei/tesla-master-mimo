import React from 'react'
import renderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ request: vi.fn(), login: vi.fn(), values: new Map<string, unknown>(), params: {} as Record<string, string>,
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Input: 'input', Picker: 'picker', Map: 'map', Slider: 'slider', Switch: 'switch', Image: 'image', Canvas: 'canvas', ScrollView: 'scroll-view' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, login: platform.login,
  getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => platform.values.set(key, value),
  removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
}, useRouter: () => ({ params: platform.params }), useDidShow: (fn: () => void) => { platform.show = fn },
useDidHide: (fn: () => void) => { platform.hide = fn }, useUnload: (fn: () => void) => { platform.unload = fn }, usePageScroll: () => {} }))

let tree: ReactTestRenderer | null = null
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.request.mockReset(); platform.login.mockReset()
})
afterEach(async () => {
  if (tree) { await act(async () => { platform.unload(); tree!.unmount() }); tree = null }
  vi.unstubAllEnvs()
})

it.each([
  ['drives', () => import('./drives/index'), {}],
  ['charges', () => import('./charges/index'), {}],
  ['analysis', () => import('./analysis/index'), { kind: 'stats' }],
  ['insights', () => import('./insights/index'), { kind: 'battery' }],
  ['tools', () => import('./tools/index'), { kind: 'report' }],
] as const)('%s waits for restoration and ignores a hidden page run', async (_name, loadPage, params) => {
  platform.params = params
  const storage = await import('../services/session')
  storage.writeWechatRecoveryPreference({ setStorageSync: (key, value) => platform.values.set(key, value) },
    { apiOrigin: 'https://api.example.test', boundUserId: 'a', enabled: true })
  platform.login.mockResolvedValue({ code: 'synthetic-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/v1/auth/wechat/restore')
    ? new Promise(resolve => { release = resolve }) : Promise.resolve({ statusCode: 200, data: { cars: [] }, header: {} }))
  const Page = (await loadPage()).default
  await act(async () => { tree = renderer.create(<Page />) })
  await act(async () => { platform.show() })
  await vi.waitFor(() => expect(platform.request).toHaveBeenCalledTimes(1))
  expect(platform.request.mock.calls[0][0].url).toContain('/v1/auth/wechat/restore')
  await act(async () => { platform.hide(); platform.show() })
  await act(async () => { release({ statusCode: 200, data: { access_token: 'restored', user: { id: 'a' } }, header: {} }); await new Promise(resolve => setTimeout(resolve, 0)) })
  const requests = platform.request.mock.calls.map(([request]) => request).filter(request => request.url.endsWith('/cars'))
  expect(requests).toHaveLength(1)
  expect(requests[0].header.Authorization).toBe('Bearer restored')
})

it.each(['expired-refresh', 'manual-after-logout'])('vehicle waits for an already running %s restore', async mode => {
  const storage = await import('../services/session'), api = await import('../services/api')
  const adapter = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, value), removeStorageSync: (key: string) => platform.values.delete(key) }
  storage.writeWechatRecoveryPreference(adapter, { apiOrigin: 'https://api.example.test', boundUserId: 'a', enabled: mode === 'expired-refresh' })
  if (mode === 'expired-refresh') storage.writeAppSession(adapter, { apiOrigin: 'https://api.example.test', accessToken: 'expired', refreshToken: 'old-refresh', expiresAt: '2000-01-01T00:00:00Z', userId: 'a', linkRequired: false })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/v1/session/refresh')) return Promise.resolve({ statusCode: 401, data: { error: 'session_required' }, header: {} })
    if (url.endsWith('/v1/auth/wechat/restore')) return new Promise(resolve => { release = resolve })
    return Promise.resolve({ statusCode: 200, data: { cars: [] }, header: {} })
  })
  const ongoing = mode === 'expired-refresh' ? api.ensureWechatSession() : api.matelinkApi.restoreWechatSession()
  await vi.waitFor(() => expect(platform.request.mock.calls.some(([request]) => request.url.endsWith('/v1/auth/wechat/restore'))).toBe(true))
  const Page = (await import('./vehicle/index')).default
  await act(async () => { tree = renderer.create(<Page />) })
  await act(async () => { platform.show(); await Promise.resolve() })
  expect(platform.request.mock.calls.some(([request]) => request.url.endsWith('/cars'))).toBe(false)
  await act(async () => { release({ statusCode: 200, data: { access_token: 'restored', user: { id: 'a' } }, header: {} }); await ongoing; await new Promise(resolve => setTimeout(resolve, 0)) })
  const requests = platform.request.mock.calls.map(([request]) => request).filter(request => request.url.endsWith('/cars'))
  expect(requests).toHaveLength(1)
  expect(requests[0].header.Authorization).toBe('Bearer restored')
})
