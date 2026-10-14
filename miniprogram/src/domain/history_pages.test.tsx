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
    expect(JSON.stringify(page.toJSON())).toContain('-0.25 kWh')
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
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
    expect(JSON.stringify(page.toJSON())).toContain('0 kWh')
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
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
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
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
    expect(JSON.stringify(page.toJSON())).toContain('-0.25 kWh')
    phase = 'quarantined'
    await click(page, '刷新')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
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
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
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
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
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
    expect(JSON.stringify(page.toJSON())).toContain('-0.25 kWh')
    expect(JSON.stringify(page.toJSON())).toContain(label)
    expect(JSON.stringify(page.toJSON())).not.toContain('尚无合格能耗依据')
  })
  it('writes detail to list/cache, preserves it through weak refresh/reopen, and revokes explicit unknown', async () => {
    let detail: unknown = { ...reported, quality_reason: 'SERVER_INTERNAL_CREDENTIAL' }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: cars.map(car => ({ ...car, current_capacity: 60 })) })
      if (url.includes('/drives/1')) return respond({ car_id: 7, vehicle_uid: 'uid7', drive: detail })
      return respond({ car_id: 7, vehicle_uid: 'uid7', drives: [{ ...summary, energy_contract: null }], meta: { page: 1, show: 20, total: 1, total_pages: 1,
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
    await click(page, '返回列表')
    await click(page, '刷新')
    expect(JSON.stringify(page.toJSON())).not.toContain('0.6 kWh')
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
    expect(readHistoryCache<{ energy: { energyKwh: number | null } }>(storage, key)[0].energy.energyKwh).toBeNull()
    await act(async () => { platform.unload(); page.unmount() }); renderer = null
    page = await mount('drives')
    expect(JSON.stringify(page.toJSON())).not.toContain('0.6 kWh')
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
    expect(readHistoryCache<{ energy: { energyKwh: number | null } }>(storage, key)[0].energy.energyKwh).toBeNull()
    expect(JSON.stringify(page.toJSON())).not.toContain('SERVER_INTERNAL_CREDENTIAL')
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
    expect(JSON.stringify(page.toJSON())).not.toContain('-0.25 kWh')
    expect(platform.values.get('matelink.selected.car.v1.user-a')).toBe('uid8')
  })
})
