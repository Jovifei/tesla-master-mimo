import React from 'react'
import renderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view', Button: 'button', Picker: 'picker', Input: 'input', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: { request: platform.request,
  getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, structuredClone(value)),
  removeStorageSync: (key: string) => platform.values.delete(key), getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) },
  useRouter: () => ({ params: { kind: 'range' } }), useDidShow: (callback: () => void) => { platform.show = callback },
  useDidHide: (callback: () => void) => { platform.hide = callback }, useUnload: (callback: () => void) => { platform.unload = callback } }))
let tree: ReactTestRenderer | null = null
const origin = 'https://api.example.test', start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:30:00Z'
const oldRaw = { drive_id: 1, session_id: 'same-session', source: 'teslamate_archive', quality_state: 'observed', start_date: start,
  end_date: end, duration_min: 30, odometer_details: { odometer_distance: 10 }, energy_consumed_net: 2 }
const newRaw = { ...oldRaw, source_instance_id: 'instance-a', source_vehicle_id: 'vehicle-a', source_record_id: 'record-a' }
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
beforeEach(() => { vi.resetModules(); platform.values.clear(); platform.request.mockReset(); vi.stubEnv('TARO_APP_API_BASE_URL', origin) })
afterEach(async () => { if (tree) await act(async () => { platform.unload(); tree!.unmount() }); tree = null; vi.unstubAllEnvs() })

it('preserves an ambiguous old archive when the real Insights consumer writes only its online result', async () => {
  const { writeAppSession } = await import('../../services/session')
  const { normalizeDrive } = await import('../../services/api')
  const { aggregateDriveHistory, isHistoryIdentityAmbiguous } = await import('../../domain/history')
  const { historyCacheKey, readHistoryCache, writeHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, structuredClone(value)),
    getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
  writeAppSession(storage, { apiOrigin: origin, accessToken: 'fixture', refreshToken: null, expiresAt: null, userId: 'owner', linkRequired: false })
  const key = historyCacheKey('drives', 'owner', 'uid7', origin)
  expect(writeHistoryCache(storage, key, [normalizeDrive(oldRaw)!])).toBe(true)
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.includes('/drives?')) return response({ drives: [newRaw], meta: { page: 1, show: 20, total_pages: 1 } })
    if (url.includes('/battery-health')) return response({ battery_health: null })
    if (url.endsWith('/status')) return response({ status: {} })
    throw new Error('unexpected synthetic route')
  })
  const Page = (await import('./index')).default
  await act(async () => { tree = renderer.create(<Page />) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  const cached = readHistoryCache<ReturnType<typeof normalizeDrive>>(storage, key).filter(row => row !== null)
  expect(cached).toHaveLength(2)
  expect(cached.some(row => isHistoryIdentityAmbiguous(row))).toBe(true)
  expect(aggregateDriveHistory(cached).distance.value).toBe(10)
  expect(tree!.root.findByProps({ className: 'insight-coverage' }).children.join('')).toContain('行驶 1 条')
  const warning = '来源身份未确认的历史记录已保留，但未计入当前展示或统计'
  const shown = () => tree!.root.findAllByType('text').map(node => node.children.join('')).join('\n')
  // The current online result has no ambiguity; only the preserved archive does.
  expect(shown()).not.toContain(warning)
  await act(async () => { platform.hide() })
  platform.request.mockRejectedValue(new Error('offline'))
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  expect(shown()).toContain(warning)
  expect(tree!.root.findByProps({ className: 'insight-coverage' }).children.join('')).toContain('行驶 1 条')
  expect(readHistoryCache<ReturnType<typeof normalizeDrive>>(storage, key)).toHaveLength(2)
})
