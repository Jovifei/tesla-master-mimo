import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Charge } from '../services/types'

const platform = vi.hoisted(() => ({
  request: vi.fn(), values: new Map<string, unknown>(), failStorage: false,
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void,
}))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({
  default: {
    request: platform.request,
    getStorageSync: (key: string) => platform.values.get(key),
    setStorageSync: (key: string, value: unknown) => { if (platform.failStorage) throw new Error('synthetic quota'); platform.values.set(key, value) },
    removeStorageSync: (key: string) => platform.values.delete(key),
    getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
  },
  useDidShow: (callback: () => void) => { platform.show = callback },
  useDidHide: (callback: () => void) => { platform.hide = callback },
  useUnload: (callback: () => void) => { platform.unload = callback },
}))

let renderer: ReactTestRenderer | null = null
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.failStorage = false; platform.request.mockReset()
  platform.values.set('matelink.wechat.session.v1', { accessToken: 'synthetic-access', refreshToken: null, expiresAt: null, userId: 'user-a', linkRequired: false })
})
afterEach(async () => {
  if (renderer) { await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null }
  vi.unstubAllEnvs()
})
const start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:01:00Z'
const summary = { drive_id: 1, session_id: 'session-1', start_date: start, end_date: end,
  source: 'telemetry_mqtt', quality_state: 'observed', duration_min: 1,
  odometer_details: { odometer_distance: 2 }, battery_details: { start_battery_level: 80, end_battery_level: 79 } }
const reported = { ...summary, energy_contract: { version: 1, net_energy: { value_kwh: -0.25, unit: 'kWh', method: 'api_reported_net', measurement_point: 'reported_net', source: 'telemetry_mqtt', source_field: 'energy_consumed_net', quality: 'reported', start_date: start, end_date: end, time_basis: 'source_report', coverage_kind: 'provider_report' } } }
const unknown = { ...reported, energy_contract: { version: 1, net_energy: { ...reported.energy_contract.net_energy, value_kwh: null, quality: 'unknown', reason: 'no_verified_driving_net_energy' } } }
const cars = [{ car_id: 7, vehicle_uid: 'uid7', name: 'Car A' }, { car_id: 8, vehicle_uid: 'uid8', name: 'Car B' }]
function respond(data: unknown) { return { statusCode: 200, data: { data }, header: {} } }
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount(kind: 'drives' | 'charges') {
  const Page = (kind === 'drives' ? await import('../pages/drives/index') : await import('../pages/charges/index')).default
  await act(async () => { renderer = TestRenderer.create(React.createElement(Page)) })
  await act(async () => { platform.show() }); await flush()
  return renderer!
}
async function click(record: ReactTestRenderer, text: string) {
  const button = record.root.findAllByType('button').find(node => node.children.join('') === text)
  expect(button, text).toBeDefined()
  await act(async () => { button!.props.onClick() }); await flush()
}

/** Actual pages -> actual API adapter -> fake Taro transport -> actual controllers/scoped storage.
 * No controller, API, energy projection or React page is mocked. */
describe('history pages consume canonical data across layers', () => {
  it('writes detail to list/cache, preserves it through weak refresh/reopen, and revokes explicit unknown', async () => {
    let detail: unknown = { ...reported, quality_reason: 'SERVER_INTERNAL_CREDENTIAL' }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ car_id: 7, vehicle_uid: 'uid7', drive: detail })
      return respond({ car_id: 7, vehicle_uid: 'uid7', drives: [summary], meta: { page: 1, show: 20, total: 1, total_pages: 1,
        availability: 'SERVER_INTERNAL_CREDENTIAL', source: 'SERVER_INTERNAL_CREDENTIAL' } })
    })
    let page = await mount('drives')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('-0.25 kWh')
    expect(JSON.stringify(page.toJSON())).not.toContain('SERVER_INTERNAL_CREDENTIAL')
    expect(page.root.findAllByProps({ className: 'history-energy-negative' }).length).toBeGreaterThan(0)
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    const key = historyCacheKey('drives', 'user-a', 'uid7', 'https://api.example.test')
    expect(readHistoryCache<{ energy: { energyKwh: number } }>(storage, key)[0].energy.energyKwh).toBe(-0.25)
    await click(page, '返回列表'); expect(JSON.stringify(page.toJSON())).not.toContain('行程详情')
    await click(page, '刷新'); expect(JSON.stringify(page.toJSON())).toContain('-0.25 kWh')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives'); expect(JSON.stringify(page.toJSON())).toContain('-0.25 kWh')
    detail = unknown
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
    expect(readHistoryCache<{ energy: { energyKwh: number | null } }>(storage, key)[0].energy.energyKwh).toBeNull()
  })

  it('restores scoped date/distance filters and offline cars authorize only cache reads', async () => {
    let offline = false
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (offline) throw new Error('private upstream diagnostic that must not be rendered')
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ drive: reported })
      return respond({ drives: [summary], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('drives')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    const distance = page.root.findAllByType('picker' as React.ElementType).find(node => node.props.range?.includes('至少 5 km'))!
    await act(async () => { distance.props.onChange({ detail: { value: 2 } }) })
    expect(JSON.stringify(page.toJSON())).toContain('没有符合筛选的行程')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    offline = true
    page = await mount('drives')
    expect(JSON.stringify(page.toJSON())).toContain('至少 5 km')
    expect(JSON.stringify(page.toJSON())).toContain('未验证在线车辆')
    expect(JSON.stringify(page.toJSON())).not.toContain('private upstream diagnostic')
    await click(page, '清除筛选')
    const before = platform.request.mock.calls.length
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(platform.request.mock.calls).toHaveLength(before)
    expect(JSON.stringify(page.toJSON())).toContain('行程详情')
    expect(page.root.findAllByType('button').some(node => node.children.join('') === '加载更多')).toBe(false)
  })

  it('closes a detail before delayed HTTP delivery without reopening it', async () => {
    let release: ((value: unknown) => void) | null = null
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return new Promise(resolve => { release = resolve })
      return respond({ drives: [summary], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('drives')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    await click(page, '返回列表')
    expect(release).not.toBeNull()
    await act(async () => { release!(respond({ drive: reported })) }); await flush()
    expect(JSON.stringify(page.toJSON())).not.toContain('行程详情')
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
  })

  it('persists actual charge details into history and survives cache quota failures online', async () => {
    const charge = { charge_id: 4, session_id: 'charge-session', start_date: start, end_date: end, source: 'teslamate_archive', quality_state: 'observed' }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.endsWith('/current')) return respond({ charge: null })
      if (url.endsWith('/charges/4')) return respond({ charge: { ...charge, address: 'Genuine detail site', charge_energy_added: 0, cost: 0, currency: 'EUR' } })
      return respond({ charges: [charge], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('charges')
    platform.failStorage = true
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('Genuine detail site')
    expect(JSON.stringify(page.toJSON())).toContain('0 EUR')
    expect(JSON.stringify(page.toJSON())).toContain('未保存到本机')
    await click(page, '返回列表')
    expect(JSON.stringify(page.toJSON())).toContain('Genuine detail site')
  })

  it.each([5, 0])('keeps genuine legacy charge %s kWh through absent/null summaries and reopen, then revokes explicit unknown', async batteryKwh => {
    const charge = { charge_id: 4, session_id: 'charge-session', start_date: start, end_date: end,
      source: 'teslamate_archive', quality_state: 'observed' }
    let summaryMode: 'absent' | 'null' = 'absent'
    let detail: unknown = { ...charge, address: 'Genuine archived charge site', charge_energy_added: batteryKwh, cost: 2, currency: 'EUR' }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.endsWith('/current')) return respond({ charge: null })
      if (url.endsWith('/charges/4')) return respond({ car_id: 7, vehicle_uid: 'uid7', charge: detail })
      return respond({ car_id: 7, vehicle_uid: 'uid7', charges: [summaryMode === 'null' ? { ...charge, charge_energy_added: null } : charge],
        meta: { page: 1, show: 20, total: 1, total_pages: 1 } })
    })
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    const key = historyCacheKey('charges', 'user-a', 'uid7', 'https://api.example.test')
    let page = await mount('charges')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain(`${batteryKwh} kWh`)
    expect(JSON.stringify(page.toJSON())).toContain('2 EUR')
    expect(readHistoryCache<Charge>(storage, key)[0].energyAddedKwh).toBe(batteryKwh)
    await click(page, '返回列表')
    await click(page, '刷新')
    expect(JSON.stringify(page.toJSON())).toContain(`${batteryKwh} kWh`)
    expect(readHistoryCache<Charge>(storage, key)[0].energyAddedKwh).toBe(batteryKwh)
    summaryMode = 'null'
    await click(page, '刷新')
    expect(JSON.stringify(page.toJSON())).toContain(`${batteryKwh} kWh`)
    expect(readHistoryCache<Charge>(storage, key)[0].energyAddedKwh).toBe(batteryKwh)
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('charges')
    expect(JSON.stringify(page.toJSON())).toContain(`${batteryKwh} kWh`)
    expect(readHistoryCache<Charge>(storage, key)[0].energyAddedKwh).toBe(batteryKwh)
    detail = { ...charge, address: 'Genuine archived charge site', cost: 2, currency: 'EUR', energy_contract: { version: 1,
      battery_input: { value_kwh: null, unit: 'kWh', source: 'teslamate_archive', quality: 'unknown',
        start_date: start, end_date: end, reason: 'charging_detail_unavailable' } } }
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(readHistoryCache<Charge>(storage, key)[0].energyAddedKwh).toBeNull()
    expect(readHistoryCache<Charge>(storage, key)[0].energyUsedKwh).toBeNull()
    expect(JSON.stringify(page.toJSON())).not.toContain(`${batteryKwh} kWh`)
    expect(JSON.stringify(page.toJSON())).toContain('2 EUR')
    await click(page, '返回列表')
    await click(page, '刷新')
    expect(readHistoryCache<Charge>(storage, key)[0].energyAddedKwh).toBeNull()
    expect(JSON.stringify(page.toJSON())).not.toContain(`${batteryKwh} kWh`)
  })

  it('commits real pagination only after a valid page and keeps newer vehicle scope on delayed delivery', async () => {
    let release: ((value: unknown) => void) | null = null
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/cars/7/drives/1')) return new Promise(resolve => { release = resolve })
      const carId = url.includes('/cars/8/') ? 8 : 7
      const page = Number(new URL(url).searchParams.get('page') || '1')
      return respond({ car_id: carId, vehicle_uid: `uid${carId}`, drives: [{ ...summary, drive_id: page,
        session_id: page === 1 ? 'session-1' : `session-${page}`, start_address: carId === 8 ? 'Car B history' : `Page ${page}` }],
        meta: { page, show: 20, total: carId === 8 ? 1 : 2, total_pages: carId === 8 ? 1 : 2 } })
    })
    const page = await mount('drives')
    await click(page, '加载更多')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
    const first = page.root.findAllByProps({ className: 'history-record' }).find(node => node.findAllByType('text').some(text => text.children.join('').includes('Page 1')))!
    await act(async () => { first.props.onClick() }); await flush()
    const selector = page.root.findAllByType('picker' as React.ElementType).find(node => node.props.range?.includes('Car A'))!
    await act(async () => { selector.props.onChange({ detail: { value: 1 } }) }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('Car B history')
    await act(async () => { release!(respond({ car_id: 7, vehicle_uid: 'uid7', drive: reported })) }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('Car B history')
    expect(JSON.stringify(page.toJSON())).not.toContain('行程详情')
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
    expect(platform.values.get('matelink.selected.car.v1.user-a')).toBe('uid8')
  })
})
