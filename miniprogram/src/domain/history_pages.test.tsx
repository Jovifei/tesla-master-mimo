import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Charge, Drive } from '../services/types'

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
  it('keeps long trips in total minutes with explicit units on the actual card and detail', async () => {
    const row = { ...reported, duration_min: 130, odometer_details: { odometer_distance: 2.04 } }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ drive: row })
      return respond({ drives: [row], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('drives')
    expect(JSON.stringify(page.toJSON())).toContain('时长 130 分钟')
    expect(JSON.stringify(page.toJSON())).not.toContain('小时')
    expect(JSON.stringify(page.toJSON())).toContain('距离 2 km')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(page.root.findAllByProps({ ariaLabel: '时长 130 分钟' })).toHaveLength(2)
    expect(JSON.stringify(page.toJSON())).toContain('总电耗 -0.25 kWh')
  })
  it('renders the four qualified trip metrics and actual partial chart, retains proof on weak refresh and rejects incompatible peak units', async () => {
    let phase: 'initial' | 'weak' | 'mph' | 'zero' = 'initial'
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ drive: { ...reported, speed_max: 0, speed_sample_count: 1, speed_metrics_source: 'observed_route_samples', speed_unit: 'km/h' } })
      const page = Number(new URL(url).searchParams.get('page') || '1')
      const row = phase === 'weak' ? { ...reported, speed_max: null, speed_sample_count: null, speed_metrics_source: null, speed_unit: null } :
        { ...reported, speed_max: phase === 'zero' ? 0 : 82, speed_sample_count: 2, speed_metrics_source: 'observed_route_samples', speed_unit: phase === 'mph' ? 'mph' : 'km/h' }
      return respond({ drives: [{ ...row, drive_id: page, session_id: `session-${page}` }], meta: { page, show: 20, total_pages: 2 } })
    })
    let page = await mount('drives')
    const card = () => page.root.findAllByProps({ className: 'history-record' })[0]
    const assertCard = (speed: string) => {
      const labels = card().findAllByProps({ className: 'metric-label' }).map(node => node.children.join(''))
      expect(labels).toEqual(['时长', '距离', '最高速度', '平均能耗'])
      const semantics = card().findAllByProps({ className: 'metric' }).map(node => node.props.ariaLabel)
      expect(semantics).toContain('时长 1 分钟')
      expect(semantics).toContain(`最高速度 ${speed} km/h`)
      expect(semantics).toContain('平均能耗 -12.5 kWh/100km')
      expect(semantics).not.toContain('总电耗 -0.25 kWh')
    }
    assertCard('82')
    expect(JSON.stringify(page.toJSON())).toContain('已加载记录统计')
    expect(JSON.stringify(page.toJSON())).toContain('不代表全部历史')
    phase = 'weak'; await click(page, '刷新'); assertCard('82')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives'); assertCard('82')
    phase = 'mph'; await click(page, '刷新'); assertCard('暂无数据')
    phase = 'zero'; await click(page, '刷新'); assertCard('0')
    await click(page, '加载更多')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
    expect(JSON.stringify(page.toJSON())).toContain('已加载行程 2 次')
    await act(async () => { card().props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('总电耗 -0.25 kWh')
    await click(page, '返回列表')
    expect(JSON.stringify(page.toJSON())).not.toContain('总电耗 -0.25 kWh')
  })
  it.each((['drives', 'charges'] as const).flatMap(kind => (['derived', 'incomplete'] as const).flatMap(grade =>
    [2, 0].map(value => ({ kind, grade, value })))))('keeps observed $kind value $value instead of laundering lower-grade $grade 99 through actual detail/page/cache/reopen', async ({ kind, grade, value }) => {
    let lowerPage = false
    const makeRaw = (amount: number, quality: string) => kind === 'drives' ? {
      ...reported, quality_state: quality, energy_contract: { version: 1, net_energy: { ...reported.energy_contract.net_energy, value_kwh: amount } },
    } : {
      charge_id: 4, session_id: 'quality-charge-session', start_date: start, end_date: end, source: 'telemetry_mqtt', quality_state: quality,
      cost: amount, currency: amount === 99 ? 'USD' : 'EUR', energy_contract: { version: 1, battery_input: {
        value_kwh: amount, unit: 'kWh', method: 'session_counter_delta', measurement_point: 'battery_input', source: 'telemetry_mqtt',
        quality: 'reported', coverage_kind: 'endpoints', coverage_ratio: 1, start_date: start, end_date: end,
      } },
    }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.endsWith('/current')) return respond({ charge: null })
      if (url.includes('/drives/1')) return respond({ drive: makeRaw(99, grade) })
      if (url.includes('/charges/4')) return respond({ charge: makeRaw(99, grade) })
      return respond({ [kind]: [makeRaw(lowerPage ? 99 : value, lowerPage ? grade : 'observed')], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, data: unknown) => { platform.values.set(key, data) } }
    const key = historyCacheKey(kind, 'user-a', 'uid7', 'https://api.example.test')
    let page = await mount(kind)
    const assertWinner = () => {
      const winner = readHistoryCache<Drive | Charge>(storage, key)[0]
      expect(winner.qualityState).toBe('observed')
      if (kind === 'drives') expect((winner as Drive).energy?.energyKwh).toBe(value)
      else {
        expect((winner as Charge).energy?.batteryInputKwh).toBe(value)
        expect((winner as Charge).cost).toBe(value)
        expect((winner as Charge).currency).toBe('EUR')
      }
      expect(JSON.stringify(page.toJSON())).toContain(kind === 'drives' ? `${value * 50} kWh/100km` : `${value} kWh`)
      expect(JSON.stringify(page.toJSON())).not.toContain(kind === 'drives' ? '4950 kWh/100km' : '99 kWh')
      expect(JSON.stringify(page.toJSON())).not.toContain('99 USD')
    }
    assertWinner()
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    assertWinner()
    await click(page, '返回列表')
    lowerPage = true
    await click(page, '刷新')
    assertWinner()
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount(kind)
    assertWinner()
  })

  it.each(['drives', 'charges'] as const)('does not upgrade a prior derived proof into a higher-grade empty whole %s record in actual cache/reopen', async kind => {
    let upgraded = false
    const metric = { value_kwh: 2, unit: 'kWh', method: 'session_counter_delta', measurement_point: 'battery_input', source: 'telemetry_mqtt',
      quality: 'reported', coverage_kind: 'endpoints', coverage_ratio: 1, start_date: start, end_date: end }
    const base = kind === 'drives' ? { ...reported, quality_state: 'derived', energy_contract: { version: 1,
      net_energy: { ...reported.energy_contract.net_energy, value_kwh: 2 } } } : {
      charge_id: 4, session_id: 'quality-charge-session', start_date: start, end_date: end, source: 'telemetry_mqtt', quality_state: 'derived',
      energy_contract: { version: 1, battery_input: metric },
    }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.endsWith('/current')) return respond({ charge: null })
      return respond({ [kind]: [upgraded ? { ...base, quality_state: 'observed', energy_contract: null,
        ...(kind === 'drives' ? { battery_details: null } : {}) } : base],
        meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, data: unknown) => { platform.values.set(key, data) } }
    const key = historyCacheKey(kind, 'user-a', 'uid7', 'https://api.example.test')
    let page = await mount(kind)
    expect(JSON.stringify(page.toJSON())).toContain(kind === 'drives' ? '100 kWh/100km' : '2 kWh')
    upgraded = true
    await click(page, '刷新')
    const assertNoGraft = () => {
      const current = readHistoryCache<Drive | Charge>(storage, key)[0]
      expect(current.qualityState).toBe('observed')
      if (kind === 'drives') expect((current as Drive).energy?.energyKwh).toBeNull()
      else expect((current as Charge).energy?.batteryInputKwh).toBeNull()
      expect(JSON.stringify(page.toJSON())).not.toContain(kind === 'drives' ? '100 kWh/100km' : '2 kWh')
    }
    assertNoGraft()
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount(kind)
    assertNoGraft()
  })

  it('does not expose isolated current/detail charges, preserves quarantine on reopen, and keeps next-page recovery', async () => {
    const charge = { charge_id: 4, session_id: 'charge-q', source: 'teslamate_archive', start_date: start, end_date: end,
      quality_state: 'observed', charge_energy_added: 6 }
    let weak = false
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.endsWith('/current') || url.endsWith('/charges/4')) return respond({ charge: { ...charge, quality_state: 'quarantined' } })
      const page = Number(new URL(url).searchParams.get('page') || 1)
      return respond({ charges: [page === 1 ? { ...charge, quality_state: weak ? undefined : 'observed' } :
        { ...charge, charge_id: 5, session_id: 'charge-valid', charge_energy_added: 0 }], meta: { page, show: 20, total_pages: 2 } })
    })
    let page = await mount('charges')
    const staleOpen = page.root.findByProps({ className: 'history-record' }).props.onClick
    expect(JSON.stringify(page.toJSON())).not.toContain('正在充电')
    await act(async () => { staleOpen() }); await flush()
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).not.toContain('充电详情')
    const calls = platform.request.mock.calls.length
    await act(async () => { staleOpen() }); await flush()
    expect(platform.request.mock.calls).toHaveLength(calls)
    weak = true; await click(page, '刷新')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('charges')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).not.toContain('6 kWh')
    await click(page, '加载更多')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
    expect(JSON.stringify(page.toJSON())).toContain('0 kWh')
  })
  it('stores actual normalized drive details without precise coordinates and preserves energy/address after reopen', async () => {
    const raw = { ...reported, start_address: 'Genuine start site', end_address: 'Genuine end site',
      start_latitude: 31.123456, start_longitude: 121.123456, end_latitude: 31.654321, end_longitude: 121.654321 }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ drive: raw })
      return respond({ drives: [summary], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('drives')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    const key = historyCacheKey('drives', 'user-a', 'uid7', 'https://api.example.test')
    const saved = JSON.stringify(platform.values.get(key))
    for (const coordinate of ['Latitude', 'Longitude', 'latitude', 'longitude', '31.123456', '121.654321']) expect(saved).not.toContain(coordinate)
    expect(readHistoryCache<Drive>(storage, key)[0].energy?.energyKwh).toBe(-0.25)
    expect(readHistoryCache<Drive>(storage, key)[0].startAddress).toBe('Genuine start site')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives')
    expect(JSON.stringify(page.toJSON())).toContain('Genuine start site')
    expect(JSON.stringify(page.toJSON())).toContain('-12.5 kWh/100km')
    expect(JSON.stringify(readHistoryCache<Drive>(storage, key))).not.toContain('Latitude')
  })

  it('excludes quarantined records with otherwise valid contracts from the trusted list and cache reopen view', async () => {
    const isolated = { ...reported, quality_state: 'quarantined', quality_reason: 'ambiguous_source' }
    const valid = { ...reported, drive_id: 2, session_id: 'session-2', energy_contract: { version: 1,
      net_energy: { ...reported.energy_contract.net_energy, value_kwh: 0 } } }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      return respond({ drives: [isolated, valid], meta: { page: 1, show: 20, total: 2, total_pages: 1 } })
    })
    let page = await mount('drives')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
    expect(JSON.stringify(page.toJSON())).toContain('（1）')
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    expect(JSON.stringify(page.toJSON())).toContain('0 kWh')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    expect(JSON.stringify(page.toJSON())).toContain('0 kWh')
  })

  it('keeps a quarantined-only first page loadable without counting it as a trusted drive', async () => {
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      const page = Number(new URL(url).searchParams.get('page') || '1')
      return respond({ drives: [page === 1 ? { ...reported, quality_state: 'quarantined' } :
        { ...reported, drive_id: 2, session_id: 'session-2', energy_contract: { version: 1,
          net_energy: { ...reported.energy_contract.net_energy, value_kwh: 0 } } }],
        meta: { page, show: 20, total: 2, total_pages: 2 } })
    })
    const page = await mount('drives')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    await click(page, '加载更多')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
    expect(JSON.stringify(page.toJSON())).toContain('（1）')
    expect(JSON.stringify(page.toJSON())).toContain('0 kWh')
  })

  it('authoritative quarantine refresh hides prior proof, blocks stale detail actions, and stays quarantined through weak refresh/reopen', async () => {
    let phase: 'valid' | 'quarantined' | 'weak' = 'valid'
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ drive: reported })
      const row = phase === 'valid' ? reported : phase === 'quarantined' ? { ...reported, quality_state: 'quarantined' } : summary
      return respond({ drives: [row], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('drives')
    const staleOpenDetail = page.root.findByProps({ className: 'history-record' }).props.onClick
    expect(JSON.stringify(page.toJSON())).toContain('-12.5 kWh/100km')
    phase = 'quarantined'
    await click(page, '刷新')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    const before = platform.request.mock.calls.length
    await act(async () => { staleOpenDetail() }); await flush()
    expect(platform.request.mock.calls).toHaveLength(before)
    expect(JSON.stringify(page.toJSON())).not.toContain('行程详情')
    phase = 'weak'
    await click(page, '刷新')
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    const key = historyCacheKey('drives', 'user-a', 'uid7', 'https://api.example.test')
    expect(readHistoryCache<Drive>(storage, key)[0].qualityState).toBe('quarantined')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    expect(readHistoryCache<Drive>(storage, key)[0].qualityState).toBe('quarantined')
  })

  it('quarantined actual detail downgrades a trusted row and never opens an energy drawer', async () => {
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ drive: { ...reported, quality_state: 'quarantined' } })
      return respond({ drives: [reported], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('drives')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).not.toContain('行程详情')
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    expect(readHistoryCache<Drive>(storage, historyCacheKey('drives', 'user-a', 'uid7', 'https://api.example.test'))[0].qualityState).toBe('quarantined')
  })

  it.each([
    ['drive_power_integral', '行驶功率积分估算', 'drive_power', 'time'],
    ['energy_remaining_delta', '电量端点估算', 'nominal_battery_remaining', 'endpoints'],
  ])('renders qualified %s with its real calculation label', async (method, label, point, coverage) => {
    const detail = { ...reported, energy_contract: { version: 1, net_energy: {
      ...reported.energy_contract.net_energy, quality: 'estimated', method, measurement_point: point,
      coverage_kind: coverage, coverage_ratio: 1, coverage_seconds: 60, source_field: 'EnergyRemaining',
      time_basis: 'collector_received_at', observed_start_at: start, observed_end_at: end,
      activity_evidence: 'continuous_drive_no_charging_with_valid_endpoints',
    } } }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.includes('/drives/1')) return respond({ drive: detail })
      return respond({ drives: [summary], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('drives')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('-12.5 kWh/100km')
    expect(JSON.stringify(page.toJSON())).toContain(label)
    expect(JSON.stringify(page.toJSON())).not.toContain('尚无合格能耗依据')
  })
  it('writes detail to list/cache, preserves it through weak refresh/reopen, and revokes explicit unknown', async () => {
    let detail: unknown = { ...reported, quality_reason: 'SERVER_INTERNAL_CREDENTIAL' }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: cars.map(car => ({ ...car, current_capacity: 60 })) })
      if (url.includes('/drives/1')) return respond({ car_id: 7, vehicle_uid: 'uid7', drive: detail })
      return respond({ car_id: 7, vehicle_uid: 'uid7', drives: [{ ...summary, energy_contract: null,
        odometer_details: { odometer_distance: null }, battery_details: { start_battery_level: null, end_battery_level: null } }], meta: { page: 1, show: 20, total: 1, total_pages: 1,
        availability: 'SERVER_INTERNAL_CREDENTIAL', source: 'SERVER_INTERNAL_CREDENTIAL' } })
    })
    let page = await mount('drives')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('-12.5 kWh/100km')
    expect(JSON.stringify(page.toJSON())).not.toContain('SERVER_INTERNAL_CREDENTIAL')
    expect(page.root.findAll(node => String(node.props.className || '').includes('history-energy-negative')).length).toBeGreaterThan(0)
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    const key = historyCacheKey('drives', 'user-a', 'uid7', 'https://api.example.test')
    const assertReportedInputs = () => {
      const cached = readHistoryCache<Drive>(storage, key)[0]
      expect(cached.distanceKm).toBe(2)
      expect(cached.startBatteryLevel).toBe(80)
      expect(cached.endBatteryLevel).toBe(79)
      expect(cached.energy?.efficiencyWhKm).toBe(-125)
      expect(JSON.stringify(page.toJSON())).toContain('2 km')
      expect(JSON.stringify(page.toJSON())).toContain('-12.5 kWh/100km')
      expect(page.root.findAllByType('text').some(node => node.children.join('') === '80% → 79%')).toBe(true)
    }
    expect(readHistoryCache<{ energy: { energyKwh: number } }>(storage, key)[0].energy.energyKwh).toBe(-0.25)
    assertReportedInputs()
    await click(page, '返回列表'); expect(JSON.stringify(page.toJSON())).not.toContain('行程详情')
    await click(page, '刷新'); expect(JSON.stringify(page.toJSON())).toContain('-12.5 kWh/100km')
    assertReportedInputs()
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives'); expect(JSON.stringify(page.toJSON())).toContain('-12.5 kWh/100km')
    assertReportedInputs()
    detail = unknown
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    expect(readHistoryCache<{ energy: { energyKwh: number | null } }>(storage, key)[0].energy.energyKwh).toBeNull()
    await click(page, '返回列表')
    await click(page, '刷新')
    expect(JSON.stringify(page.toJSON())).not.toContain('30 kWh/100km')
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    expect(readHistoryCache<{ energy: { energyKwh: number | null } }>(storage, key)[0].energy.energyKwh).toBeNull()
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives')
    expect(JSON.stringify(page.toJSON())).not.toContain('30 kWh/100km')
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    expect(readHistoryCache<{ energy: { energyKwh: number | null } }>(storage, key)[0].energy.energyKwh).toBeNull()
    expect(JSON.stringify(page.toJSON())).not.toContain('SERVER_INTERNAL_CREDENTIAL')
    detail = { ...reported, energy_contract: { version: 1, net_energy: { ...reported.energy_contract.net_energy, value_kwh: 0 } } }
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(readHistoryCache<Drive>(storage, key)[0].energy?.energyKwh).toBe(0)
    expect(JSON.stringify(page.toJSON())).toContain('0 kWh')
    await click(page, '返回列表')
    await click(page, '刷新')
    expect(readHistoryCache<Drive>(storage, key)[0].energy?.energyKwh).toBe(0)
  })

  it('preserves real SOC and distance on compatible weak null refresh/reopen, but applies non-null corrections and zeros', async () => {
    let phase: 'original' | 'weak-null' | 'corrected' | 'zero' | 'reported-zero' = 'original'
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: cars.map(car => ({ ...car, current_capacity: 78.4 })) })
      const raw = phase === 'weak-null' ? { ...summary, energy_contract: null,
        odometer_details: { odometer_distance: null }, battery_details: { start_battery_level: null, end_battery_level: null } } :
        phase === 'corrected' ? { ...summary, odometer_details: { odometer_distance: 4 },
          battery_details: { start_battery_level: 80, end_battery_level: 78 } } :
        phase === 'zero' || phase === 'reported-zero' ? { ...summary, odometer_details: { odometer_distance: 0 },
          battery_details: { start_battery_level: 0, end_battery_level: 0 },
          ...(phase === 'reported-zero' ? { energy_contract: { version: 1,
            net_energy: { ...reported.energy_contract.net_energy, value_kwh: 0 } } } : {}) } : summary
      return respond({ drives: [raw], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    const key = historyCacheKey('drives', 'user-a', 'uid7', 'https://api.example.test')
    let page = await mount('drives')
    const assertOriginal = () => {
      const cached = readHistoryCache<Drive>(storage, key)[0]
      expect(cached.energy?.energyKwh).toBeCloseTo(0.784)
      expect(cached.energy?.efficiencyWhKm).toBeCloseTo(392)
      expect(cached.distanceKm).toBe(2)
      expect(cached.startBatteryLevel).toBe(80)
      expect(cached.endBatteryLevel).toBe(79)
      expect(page.root.findAllByType('text').some(node => node.children.join('') === '80% → 79%')).toBe(true)
    }
    assertOriginal()
    phase = 'weak-null'
    await click(page, '刷新')
    assertOriginal()
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives')
    assertOriginal()
    phase = 'corrected'
    await click(page, '刷新')
    const corrected = readHistoryCache<Drive>(storage, key)[0]
    expect(corrected.energy?.energyKwh).toBeCloseTo(1.568)
    expect(corrected.distanceKm).toBe(4)
    expect(corrected.endBatteryLevel).toBe(78)
    expect(page.root.findAllByType('text').some(node => node.children.join('') === '80% → 78%')).toBe(true)
    phase = 'zero'
    await click(page, '刷新')
    const zero = readHistoryCache<Drive>(storage, key)[0]
    expect(zero.distanceKm).toBe(0)
    expect(zero.startBatteryLevel).toBe(0)
    expect(zero.endBatteryLevel).toBe(0)
    expect(zero.energy?.energyKwh).toBeNull() // Equal integer SOC does not prove zero kWh.
    expect(zero.energy?.efficiencyWhKm).toBeNull()
    expect(page.root.findAllByType('text').some(node => node.children.join('') === '0% → 0%')).toBe(true)
    expect(JSON.stringify(page.toJSON())).toContain('0 km')
    phase = 'reported-zero'
    await click(page, '刷新')
    expect(readHistoryCache<Drive>(storage, key)[0].energy?.energyKwh).toBe(0)
    expect(JSON.stringify(page.toJSON())).toContain('平均能耗 暂无数据 kWh/100km') // Zero distance has no per-distance efficiency.
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
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
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

  it.each(['dc', undefined])('keeps archive input 6 kWh with mode %s separate from qualified AC through cache/reopen and unknown revocation', async mode => {
    const raw = { charge_id: 4, session_id: 'charge-session', start_date: start, end_date: end,
      source: 'teslamate_archive', quality_state: 'observed', charge_mode: mode, charge_energy_used: 6,
      charge_energy_added: 5, cost: 2, currency: 'EUR' }
    let detail: unknown = raw
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.endsWith('/current')) return respond({ charge: null })
      if (url.endsWith('/charges/4')) return respond({ charge: detail })
      return respond({ charges: [raw], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    const key = historyCacheKey('charges', 'user-a', 'uid7', 'https://api.example.test')
    let page = await mount('charges')
    expect(readHistoryCache<Charge>(storage, key)[0].energy?.inputEnergyKwh).toBe(6)
    expect(readHistoryCache<Charge>(storage, key)[0].energy?.acInputKwh).toBeNull()
    expect(JSON.stringify(page.toJSON())).toContain('输入电量（历史报告）')
    expect(JSON.stringify(page.toJSON())).toContain('6 kWh')
    expect(JSON.stringify(page.toJSON())).not.toContain('AC 输入')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('输入电量（历史报告）')
    expect(JSON.stringify(page.toJSON())).not.toContain('AC 输入')
    await click(page, '返回列表')
    await click(page, '刷新')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('charges')
    expect(readHistoryCache<Charge>(storage, key)[0].energy?.inputEnergyKwh).toBe(6)
    expect(readHistoryCache<Charge>(storage, key)[0].energy?.acInputKwh).toBeNull()
    expect(JSON.stringify(page.toJSON())).toContain('6 kWh')
    expect(JSON.stringify(page.toJSON())).not.toContain('AC 输入')
    detail = { ...raw, energy_contract: { version: 1, battery_input: { quality: 'unknown', value_kwh: null },
      ac_input: { quality: 'unknown', value_kwh: null } } }
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(readHistoryCache<Charge>(storage, key)[0].energy?.inputEnergyKwh).toBeNull()
    expect(readHistoryCache<Charge>(storage, key)[0].energy?.acInputKwh).toBeNull()
    expect(JSON.stringify(page.toJSON())).not.toContain('6 kWh')
    expect(JSON.stringify(page.toJSON())).toContain('2 EUR')
  })

  it('labels input as AC only for an independently qualified AC contract in the actual charge page and cache', async () => {
    const battery = { value_kwh: 5, unit: 'kWh', method: 'session_counter_delta', measurement_point: 'battery_input',
      source: 'telemetry_mqtt', quality: 'reported', coverage_kind: 'endpoints', coverage_ratio: 1, start_date: start, end_date: end }
    const raw = { charge_id: 4, session_id: 'charge-session', start_date: start, end_date: end,
      source: 'telemetry_mqtt', quality_state: 'observed', energy_contract: { version: 1, battery_input: battery,
        ac_input: { ...battery, value_kwh: 6, measurement_point: 'ac_charger_input' },
        charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict' } }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars })
      if (url.endsWith('/current')) return respond({ charge: null })
      if (url.endsWith('/charges/4')) return respond({ charge: raw })
      return respond({ charges: [raw], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('charges')
    expect(JSON.stringify(page.toJSON())).toContain('AC 输入')
    expect(JSON.stringify(page.toJSON())).toContain('AC 充电')
    expect(JSON.stringify(page.toJSON())).toContain('6 kWh')
    expect(JSON.stringify(page.toJSON())).not.toContain('输入电量（历史报告）')
    await act(async () => { page.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('AC 输入')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('charges')
    expect(JSON.stringify(page.toJSON())).toContain('AC 输入')
    expect(JSON.stringify(page.toJSON())).toContain('AC 充电')
    const { historyCacheKey, readHistoryCache } = await import('../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
    expect(readHistoryCache<Charge>(storage, historyCacheKey('charges', 'user-a', 'uid7', 'https://api.example.test'))[0].energy?.acInputKwh).toBe(6)
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
    expect(JSON.stringify(page.toJSON())).not.toContain('-12.5 kWh/100km')
    expect(platform.values.get('matelink.selected.car.v1.user-a')).toBe('uid8')
  })
})
