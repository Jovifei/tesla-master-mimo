import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { diagnosticSteps } from '../../domain/diagnostic'
import type { ReadinessItem, TelemetryPairing } from '../../services/types'

const fixture = JSON.parse(readFileSync(resolve(process.cwd(), '../fixtures/safety-stage-v1.json'), 'utf8')) as {
  readiness_cases: Array<{ id: string; scope: { account: string; origin: string; vehicle_uid: string }; rawStatus: Record<string, unknown> | null;
    readiness: { items: ReadinessItem[] }; pairing: TelemetryPairing | null; expected: { receive: string; qualification: string; history: string } }>
}

const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), quota: false,
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => { if (platform.quota) throw Error('fixture quota'); platform.values.set(key, value) },
  removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn },
useUnload: (fn: () => void) => { platform.unload = fn } }))

let renderer: ReactTestRenderer | null = null
const cars = [{ car_id: 7, vehicle_uid: 'uid7', name: 'Car A' }, { car_id: 8, vehicle_uid: 'uid8', name: 'Car B' }]
const respond = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
const session = (userId: string) => ({ accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
const report = (power: number) => ({ status: { state: 'online', battery_details: { battery_level: 0, rated_battery_range: 0 },
  odometer: 0, car_geodata: { latitude: 0, longitude: 0 }, car_status: { locked: false },
  climate_details: { is_climate_on: false }, driving_details: { speed: 0, power }, charging_details: { charger_power: 0 } } })
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.quota = false; platform.request.mockReset()
  platform.values.set('matelink.wechat.session.v1', session('a'))
})
afterEach(async () => {
  if (renderer) { await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null }
  vi.unstubAllEnvs()
})
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount() {
  const Page = (await import('./index')).default
  await act(async () => { renderer = TestRenderer.create(React.createElement(Page)) })
  await act(async () => { platform.show() }); await flush()
  return renderer!
}
const visible = () => JSON.stringify(renderer!.toJSON())
async function diagnosticEvidence() {
  const Checklist = (await import('../../components/ReadinessChecklist')).default
  const props = renderer!.root.findByType(Checklist).props
  return diagnosticSteps({ ...props.diagnostics, items: props.items, pairing: props.pairing, sessionReady: props.sessionReady })
}
async function choose(index: number) {
  await act(async () => { renderer!.root.findByType('picker' as React.ElementType).props.onChange({ detail: { value: index } }) })
  await flush()
}
function online(url: string) {
  if (url.endsWith('/cars')) return respond({ cars })
  if (url.endsWith('/status')) return respond(report(url.includes('/cars/8/') ? -22 : -11))
  if (url.endsWith('/data-readiness')) return respond({ items: [] })
  return respond({ status: 'available' })
}

it.each(fixture.readiness_cases)('shared synthetic $id runs through the API normalizer and actual diagnostic component', async row => {
  vi.stubEnv('TARO_APP_API_BASE_URL', row.scope.origin)
  platform.values.set('matelink.wechat.session.v1', session(row.scope.account))
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return respond({ cars: [{ car_id: 7, vehicle_uid: row.scope.vehicle_uid }] })
    if (url.endsWith('/status')) return respond({ status: row.rawStatus ?? {}, observed_at: row.rawStatus?.observed_at, source: row.rawStatus?.source })
    if (url.endsWith('/data-readiness')) return respond(row.readiness)
    return respond(row.pairing ?? {})
  })
  await mount()
  const steps = await diagnosticEvidence()
  const byKey = Object.fromEntries(steps.map(step => [step.key, step]))
  expect(byKey.qualification.state).toBe(row.expected.qualification)
  expect(byKey.history.state).toBe(row.expected.history)
  expect(byKey.receive.state).toBe(row.expected.receive)
  if (row.id === 'old_timestamp') expect(byKey.receive.freshness).toBe('stale_or_skewed')
  expect(visible()).toContain('订阅通知尚未接入（UNSUPPORTED）')
  expect(visible()).toContain('小程序胎压历史尚未接入（UNSUPPORTED）')
  expect(platform.request).toHaveBeenCalledTimes(4)
})

it('actual scoped local cache proves saved summaries independently of current quality or receipt', async () => {
  const { writeHistoryCache, historyCacheKey } = await import('../../services/history_cache')
  const origin = 'https://api.example.test'
  writeHistoryCache({ setStorageSync: (key, value) => platform.values.set(key, value) }, historyCacheKey('drives', 'other-account', 'uid7', origin), [{ id: 'private', qualityState: 'observed' }])
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  await mount()
  expect((await diagnosticEvidence())[4].state).toBe('UNKNOWN')
  writeHistoryCache({ setStorageSync: (key, value) => platform.values.set(key, value) }, historyCacheKey('drives', 'a', 'uid7', origin), [
    { id: 'incomplete', qualityState: 'incomplete' }, { id: 'quarantined', qualityState: 'quarantined' },
  ])
  await act(async () => { platform.show() }); await flush()
  const steps = await diagnosticEvidence()
  expect(steps[3].state).toBe('WARNING')
  expect(steps[4].state).toBe('PASS')
  expect(steps[4].summary).toContain('2 条历史摘要')
  expect(visible()).not.toContain('private')
  await choose(1)
  expect((await diagnosticEvidence())[4].state).toBe('UNKNOWN')
})

it('drops a delayed response after API origin changes without writing its status cache', async () => {
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/cars/7/status')
    ? new Promise(resolve => { release = resolve }) : Promise.resolve(online(url)))
  await mount()
  const api = await import('../../services/api')
  const origin = vi.spyOn(api, 'getApiOrigin').mockReturnValue('https://other.example.test')
  await act(async () => { release(respond(report(-99))) }); await flush()
  expect(visible()).not.toContain('-99 kW')
  expect([...platform.values.keys()].some(key => key.startsWith('matelink.status.'))).toBe(false)
  origin.mockRestore()
})

it('rejects late evidence across a same-account logout and new session generation', async () => {
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/cars/7/status')
    ? new Promise(resolve => { release = resolve }) : Promise.resolve(online(url)))
  await mount()
  const api = await import('../../services/api')
  await api.matelinkApi.logout()
  platform.values.set('matelink.wechat.session.v1', session('a'))
  await act(async () => { release(respond(report(-99))) }); await flush()
  expect(visible()).not.toContain('-99 kW')
  expect([...platform.values.keys()].some(key => key.startsWith('matelink.status.'))).toBe(false)
})

it('clears old account selection before a stale picker can expose another account vehicle', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  await mount()
  platform.values.set('matelink.wechat.session.v1', session('b'))
  const before = platform.request.mock.calls.length
  await choose(1)
  expect(visible()).not.toContain('Car A')
  expect(visible()).not.toContain('Car B')
  expect(visible()).not.toContain('-22 kW')
  expect(platform.request).toHaveBeenCalledTimes(before)
})

/** Real React page and API/cache layers; only the Taro platform is replaced. */
it('renders genuine zero, false and negative power without changing their meaning', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  await mount()
  expect(visible()).toContain('0%')
  expect(visible()).toContain('0 km')
  expect(visible()).toContain('0 km/h')
  expect(visible()).toContain('-11 kW')
  expect(visible()).toContain('未锁')
  expect(visible()).toContain('关闭')
  expect(visible()).toContain('0.00000, 0.00000')
})

it('offline cached cars support only scoped display and selection, never numeric-ID requests', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  await mount()
  await choose(1)
  await choose(0)
  await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null
  platform.request.mockReset().mockRejectedValue(Error('PRIVATE_UPSTREAM_DETAIL'))
  await mount()
  expect(visible()).toContain('-11 kW')
  expect(visible()).not.toContain('PRIVATE_UPSTREAM_DETAIL')
  expect(platform.request).toHaveBeenCalledTimes(1)
  await choose(1)
  expect(visible()).toContain('-22 kW')
  expect(platform.request).toHaveBeenCalledTimes(1)
})

it('clears old vehicle reports immediately and rejects a late response after vehicle switch', async () => {
  let defer = false, release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (defer && url.endsWith('/cars/7/status')) return new Promise(resolve => { release = resolve })
    return Promise.resolve(online(url))
  })
  await mount()
  expect(visible()).toContain('-11 kW')
  defer = true
  await act(async () => { platform.show() }); await flush()
  expect(visible()).not.toContain('-11 kW')
  await choose(1)
  expect(visible()).toContain('-22 kW')
  await act(async () => { release(respond(report(-99))) }); await flush()
  expect(visible()).toContain('-22 kW')
  expect(visible()).not.toContain('-99 kW')
})

it('clears account A data and drops its delayed status when account B becomes current', async () => {
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url, header }: { url: string; header: { Authorization?: string } }) => {
    if (url.endsWith('/cars') && header.Authorization === 'Bearer fixture-b') return Promise.resolve(respond({ cars: [cars[1]] }))
    if (url.endsWith('/cars/7/status')) return new Promise(resolve => { release = resolve })
    return Promise.resolve(online(url))
  })
  await mount()
  platform.values.set('matelink.wechat.session.v1', session('b'))
  await act(async () => { platform.show() }); await flush()
  expect(visible()).toContain('Car B')
  expect(visible()).not.toContain('Car A')
  await act(async () => { release(respond(report(-99))) }); await flush()
  expect(visible()).toContain('-22 kW')
  expect(visible()).not.toContain('-99 kW')
})

it('invalid API configuration produces a safe page error without an unhandled fallback failure', async () => {
  vi.stubEnv('TARO_APP_API_BASE_URL', 'https://user:password@api.example.test')
  await mount()
  expect(visible()).toContain('服务地址尚未配置')
  expect(visible()).not.toContain('password')
  expect(platform.request).not.toHaveBeenCalled()
})

it('selection storage quota does not block online reports or revert an in-memory vehicle choice', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  platform.quota = true
  await mount()
  expect(visible()).toContain('-11 kW')
  await choose(1)
  expect(visible()).toContain('Car B')
  expect(visible()).toContain('-22 kW')
  expect(platform.request.mock.calls.some(([value]) => value.url.endsWith('/cars/8/status'))).toBe(true)
})
