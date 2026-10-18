import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), params: {} as Record<string, string>,
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void,
  navigateTo: vi.fn(), navigateBack: vi.fn(), context: { beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(), setStrokeStyle: vi.fn(), setLineWidth: vi.fn(), setFillStyle: vi.fn(), fillText: vi.fn(), clearRect: vi.fn(), arc: vi.fn(), fill: vi.fn(), draw: vi.fn() },
}))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Canvas: 'canvas', Slider: 'slider', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, navigateTo: platform.navigateTo, navigateBack: platform.navigateBack,
  getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
  createCanvasContext: () => platform.context,
  createSelectorQuery: () => { const query = { select: () => query, boundingClientRect: (callback: (rect: { width: number; height: number }) => void) => { callback({ width: 280, height: 180 }); return query }, exec: () => {} }; return query },
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn }, useUnload: (fn: () => void) => { platform.unload = fn }, useRouter: () => ({ params: platform.params }) }))

let renderer: ReactTestRenderer | null = null
const start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:10:00Z'
const car = { car_id: 7, vehicle_uid: 'analysis-car', name: 'Analysis car' }
const raw = (id: number, kwh: number | null = -0.5) => ({ drive_id: id, session_id: `analysis-${id}`, start_date: start, end_date: end,
  duration_min: 10, source: 'telemetry_mqtt', quality_state: 'observed', odometer_details: { odometer_distance: 10 },
  battery_details: { start_battery_level: 80, end_battery_level: 79 }, energy_contract: { version: 1, net_energy: {
    value_kwh: kwh, unit: 'kWh', method: 'api_reported_net', measurement_point: 'reported_net', source: 'telemetry_mqtt', quality: kwh == null ? 'unknown' : 'reported', start_date: start, end_date: end,
  } } })
const respond = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount(kind: string, extra: Record<string, string> = {}) {
  platform.params = { kind, ...extra }
  const Page = (await import('./index')).default
  await act(async () => { renderer = TestRenderer.create(<Page />) })
  await act(async () => { platform.show() }); await flush()
  return renderer!
}
async function click(page: ReactTestRenderer, label: string) {
  const button = page.root.findAllByType('button').find(node => node.children.join('') === label)
  expect(button, label).toBeDefined()
  await act(async () => { button!.props.onClick() }); await flush()
}
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.request.mockReset(); platform.navigateTo.mockReset()
  Object.values(platform.context).forEach(fn => fn.mockClear())
  platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', accessToken: 'synthetic', refreshToken: null, userId: 'analysis-user', expiresAt: null, linkRequired: false })
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return respond({ cars: [car] })
    if (url.includes('/standby')) return respond({ windows: [], availability: 'collecting', source: 'local_history' })
    if (url.includes('/history-context')) return respond({ car_id: 7, vehicle_uid: 'analysis-car', capability_version: 1 })
    if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
    return respond({ drives: [raw(1), raw(2, null)], meta: { page: 1, show: 20, total_pages: 1 } })
  })
})
afterEach(async () => { if (renderer) await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null; vi.unstubAllEnvs() })

describe('advanced App entry pages through actual authenticated consumers', () => {
  it('draws isolated genuine zero and signed observations as points without bridging unknown power gaps', async () => {
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/drives/1')) return respond({ drive: { ...raw(1), drive_details: [0, null, -5, null, 5].map((power, index) => ({ date: new Date(Date.parse(start) + index * 60000).toISOString(), power })) } })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('curves', { id: '1' })
    expect(page.root.findAllByType('canvas' as React.ElementType)).toHaveLength(1)
    expect(platform.context.arc).toHaveBeenCalledTimes(3)
    expect(platform.context.lineTo).toHaveBeenCalledTimes(2) // baseline and cursor only, no source-gap bridge.
  })

  it('renders all five actual nested charging curves with same-response Fahrenheit conversion and no sample persistence', async () => {
    const charge = { charge_id: 4, source: 'teslamate_archive', quality_state: 'observed', start_date: start, end_date: end, charge_energy_added: 3, cost: null,
      charge_details: [
        { date: start, battery_level: 0, outside_temp: 32, charger_details: { charger_power: -2, charger_voltage: 0, charger_actual_current: 0 } },
        { date: '2026-10-14T00:05:00Z', battery_level: 50, outside_temp: 50, charger_details: { charger_power: 0, charger_voltage: 230, charger_actual_current: 16 } },
        { date: end, battery_level: 100, outside_temp: 68, charger_details: { charger_power: 7, charger_voltage: 230, charger_actual_current: 32 } },
      ] }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/charges/4')) return respond({ units: { unit_of_temperature: 'F' }, charge })
      if (url.includes('/charges')) return respond({ charges: [{ ...charge, charge_details: undefined }], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('chargeCurves', { chargeId: '4' })
    const curves = page.root.findAllByProps({ className: 'card analysis-curve' })
    expect(curves.map(node => node.findByProps({ className: 'section-title' }).children.join(''))).toEqual(['充电电量变化', '充电功率', '充电电压', '充电电流', '充电温度'])
    expect(page.root.findAllByType('canvas' as React.ElementType)).toHaveLength(5)
    expect(curves[1].findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('-2 kW')
    expect(curves[4].findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('0 °C')
    await act(async () => { curves[1].findByType('slider' as React.ElementType).props.onChange({ detail: { value: 1 } }) })
    expect(curves[1].findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('0 kW')
    expect(curves[2].findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('230 V')
    expect(curves[3].findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('16 A')
    expect(curves[4].findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('10 °C')
    expect(page.root.findByProps({ ariaLabel: '费用 暂无数据 币种未知' })).toBeDefined()
    expect(JSON.stringify([...platform.values.values()])).not.toMatch(/charge_details|charger_actual_current|outside_temp/)
  })

  it('keeps missing charging samples unknown and rejects a late hidden charge waveform', async () => {
    let release: ((value: unknown) => void) | null = null
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/charges/4')) return new Promise(resolve => { release = resolve })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('chargeCurves', { chargeId: '4' })
    await act(async () => { platform.hide() })
    await act(async () => { release!(respond({ charge: { charge_id: 4, source: 'teslamate_archive', start_date: start, end_date: end, charge_details: [{ date: start, battery_level: 50 }] } })) }); await flush()
    expect(page.root.findAllByType('canvas' as React.ElementType)).toHaveLength(0)
    expect(page.root.findAllByProps({ className: 'card analysis-curve' })).toHaveLength(0)
  })

  it('keeps a charge without time samples explicitly unavailable and clears the current page when its session expires', async () => {
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/charges/4')) return respond({ charge: { charge_id: 4, source: 'teslamate_archive', start_date: start, end_date: end } })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('chargeCurves', { chargeId: '4' })
    expect(page.root.findAllByType('canvas' as React.ElementType)).toHaveLength(0)
    expect(JSON.stringify(page.toJSON())).toContain('缺少合格的')
    await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      platform.values.delete('matelink.wechat.session.v1')
      throw new Error('synthetic session expired')
    })
    page = await mount('stats')
    expect(JSON.stringify(page.toJSON())).toContain('会话或服务地址已变化')
    expect(page.root.findAllByProps({ className: 'analysis-coverage' })).toHaveLength(0)
    expect(page.root.findAllByProps({ className: 'loading-indicator loading-indicator-compact' })).toHaveLength(0)
  })

  it('uses scoped manual amounts in statistics and timeline while raw cache/detail retain the actual upstream bill', async () => {
    const charge = { charge_id: 9, session_id: 'cost-nine', source: 'teslamate_archive', quality_state: 'observed', start_date: start, end_date: end, charge_energy_added: 3, cost: 0, currency: 'EUR' }
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/charges/9')) return respond({ charge })
      if (url.includes('/charges')) return respond({ charges: [charge], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('stats')
    const { writeChargeCostOverride } = await import('../../services/charge_cost')
    const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, removeStorageSync: (key: string) => { platform.values.delete(key) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
    const key = historyCacheKey('charges', 'analysis-user', 'analysis-car', 'https://api.example.test')
    const original = readHistoryCache<import('../../services/types').Charge>(storage, key)[0]
    expect(writeChargeCostOverride(storage, { userId: 'analysis-user', origin: 'https://api.example.test', stableId: 'analysis-car' }, original, 7, 'CNY', () => true)).toBe(true)
    await click(page, '刷新')
    expect(page.root.findByProps({ ariaLabel: '已知费用 1/1 条 7 CNY' })).toBeDefined()
    expect(readHistoryCache<import('../../services/types').Charge>(storage, key)[0]).toMatchObject({ cost: 0, currency: 'EUR' })
    await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null
    page = await mount('timeline')
    expect(JSON.stringify(page.toJSON())).toContain('7 CNY')
    expect(JSON.stringify(page.toJSON())).toContain('本机手动金额')
    await click(page, '查看充电 9')
    expect(page.root.findByProps({ className: 'card analysis-charge-detail' }).findByProps({ ariaLabel: '费用 7 CNY' })).toBeDefined()
    expect(readHistoryCache<import('../../services/types').Charge>(storage, key)[0]).toMatchObject({ cost: 0, currency: 'EUR' })
  })

  it('caps native bridge drawing for 20,000 source samples while the cursor still reads every original sample', async () => {
    const points = Array.from({ length: 20_000 }, (_, index) => ({ date: new Date(Date.parse(start) + index * 30).toISOString(),
      speed: index % 90, power: index % 3 === 0 ? -12 : index % 3 === 1 ? 0 : 20, battery_level: 80 - index / 20000, elevation: index % 100 }))
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/drives/1')) return respond({ units: { unit_of_length: 'km' }, drive: { ...raw(1), drive_details: points } })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('curves', { id: '1' })
    expect(page.root.findAllByType('canvas' as React.ElementType)).toHaveLength(5)
    expect(platform.context.moveTo.mock.calls.length + platform.context.lineTo.mock.calls.length).toBeLessThanOrEqual(5 * 604)
    expect(page.root.findAllByProps({ className: 'muted analysis-plot-thinned' })).toHaveLength(5)
    const power = page.root.findAllByProps({ className: 'card analysis-curve' }).find(node => node.findAllByType('text').some(text => text.children.join('') === '功率输出与回收'))!
    const slider = power.findByType('slider' as React.ElementType)
    expect(slider.props.max).toBe(19999)
    await act(async () => { slider.props.onChange({ detail: { value: 19000 } }) })
    expect(power.findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('0 kW')
    expect(JSON.stringify([...platform.values.values()])).not.toContain('drive_details')
  })

  it('draws actual timestamped curve samples with zero and negative power, allows a sample cursor and never persists locations', async () => {
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/drives/1')) return respond({ units: { unit_of_length: 'km' }, drive: { ...raw(1), drive_details: [
        { date: start, speed: 0, power: -5, battery_level: 80, elevation: 0, latitude: 31.123456, longitude: 121.654321 },
        { date: '2026-10-14T00:05:00Z', speed: 30, power: 0, battery_level: 79.5, elevation: 5 },
        { date: end, speed: 60, power: 5, battery_level: 79, elevation: 10 },
      ] } })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('curves', { id: '1' })
    expect(page.root.findAllByType('canvas' as React.ElementType)).toHaveLength(5)
    expect(platform.context.draw).toHaveBeenCalledTimes(5)
    expect(platform.context.lineTo.mock.calls.length).toBeGreaterThanOrEqual(8)
    const power = page.root.findAllByProps({ className: 'card analysis-curve' }).find(node => node.findAllByType('text').some(text => text.children.join('') === '功率输出与回收'))!
    expect(power.findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('-5 kW')
    await act(async () => { power.findByType('slider' as React.ElementType).props.onChange({ detail: { value: 1 } }) })
    expect(power.findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('0 kW')
    const cursorTimes = page.root.findAllByProps({ className: 'analysis-cursor' }).map(node => node.children.join('').split(' · ')[0])
    expect(new Set(cursorTimes).size).toBe(1)
    expect(cursorTimes[0]).toContain(':05')
    await act(async () => { power.findByProps({ className: 'analysis-curve-expand' }).props.onClick() })
    expect(page.root.findAllByProps({ className: 'card analysis-curve analysis-curve-fullscreen' })).toHaveLength(1)
    await act(async () => { page.root.findByProps({ className: 'card analysis-curve analysis-curve-fullscreen' }).findByProps({ className: 'analysis-curve-expand' }).props.onClick() })
    expect(page.root.findAllByProps({ className: 'card analysis-curve' })).toHaveLength(5)
    expect(JSON.stringify([...platform.values.values()])).not.toMatch(/31\.123456|121\.654321|latitude|longitude|drive_details/)
  })

  it('does not render quarantined curve evidence or turn a failed empty analysis into zero statistics', async () => {
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/drives/1')) return respond({ drive: { ...raw(1), quality_state: 'quarantined', drive_details: [{ date: start, power: 500 }, { date: end, power: 500 }] } })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('curves', { id: '1' })
    expect(page.root.findAllByType('canvas' as React.ElementType)).toHaveLength(0)
    await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null
    platform.values.clear()
    platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', accessToken: 'synthetic', userId: 'analysis-user', expiresAt: null })
    platform.request.mockImplementation(async ({ url }: { url: string }) => { if (url.endsWith('/cars')) return respond({ cars: [car] }); throw new Error('offline') })
    page = await mount('stats')
    expect(page.root.findByProps({ ariaLabel: '行程次数 暂无数据 次' })).toBeDefined()
    expect(page.root.findByProps({ ariaLabel: '充电次数 暂无数据 次' })).toBeDefined()
  })
  it('renders real adjacent parked evidence without capacity guesses and excludes a charging overlap from vampire totals', async () => {
    const drives = [{ ...raw(1), start_date: '2026-10-14T00:00:00Z', end_date: '2026-10-14T00:10:00Z' },
      { ...raw(2), start_date: '2026-10-14T02:00:00Z', end_date: '2026-10-14T02:10:00Z', battery_details: { start_battery_level: 78, end_battery_level: 77 } }]
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.includes('/parked/1/2')) return respond({ older_drive_id: 1, newer_drive_id: 2, start_date: '2026-10-14T00:10:00Z', end_date: '2026-10-14T02:00:00Z',
        start_battery_level: 79, end_battery_level: 78, battery_delta: -1, source: 'database_latest', sample_count: 0, energy_kwh: 99 })
      if (url.includes('/standby')) return respond({ windows: [], meta: { availability: 'collecting', source: 'local_history' } })
      if (url.includes('/charges')) return respond({ charges: [{ charge_id: 3, source: 'teslamate_archive', start_date: '2026-10-14T01:00:00Z', end_date: '2026-10-14T01:30:00Z' }], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives, meta: { page: 1, show: 20, total_pages: 1 } })
    })
    let page = await mount('parked', { olderId: '1', newerId: '2' })
    expect(page.root.findByProps({ ariaLabel: '电量变化 -1%' })).toBeDefined()
    expect(page.root.findByProps({ ariaLabel: '合格储能变化 暂无数据 kWh' })).toBeDefined()
    expect(JSON.stringify(page.toJSON())).toContain('与充电记录重叠')
    expect(JSON.stringify(page.toJSON())).not.toContain('99 kWh')
    await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null
    page = await mount('vampire')
    expect(JSON.stringify(page.toJSON())).toContain('含充电重叠的窗口已排除损耗计算')
    expect(page.root.findByProps({ ariaLabel: '合格能量变化 暂无数据 kWh' })).toBeDefined()
  })

  it('opens a deep-linked actual charge detail outside a bounded history list and preserves fresh cache rows', async () => {
    const charge = (id: number) => ({ charge_id: id, source: 'teslamate_archive', quality_state: 'observed', start_date: start, end_date: end, charge_energy_added: id, cost: 0, currency: 'EUR' })
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.endsWith('/charges/9')) return respond({ charge: charge(9) })
      if (url.includes('/charges')) return respond({ charges: [charge(1)], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1)], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('timeline', { chargeId: '9' })
    const detail = page.root.findByProps({ className: 'card analysis-charge-detail' })
    expect(detail.findByProps({ ariaLabel: '电池充入 9 kWh' })).toBeDefined()
    expect(detail.findByProps({ ariaLabel: '费用 0 EUR' })).toBeDefined()
    const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
    const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync() {} }
    const saved = readHistoryCache(storage, historyCacheKey('charges', 'analysis-user', 'analysis-car', 'https://api.example.test')) as { id: string }[]
    expect(saved.map(row => row.id).sort()).toEqual(['1', '9'])
  })

  it.each([{ kind: 'stats', title: '综合分析' }, { kind: 'efficiency', title: '能耗趋势' }, { kind: 'timeline', title: '行程与充电' }, { kind: 'mileage', title: '年度里程' }])('renders meaningful $kind hierarchy from scoped data', async ({ kind, title }) => {
    const page = await mount(kind)
    expect(JSON.stringify(page.toJSON())).toContain(title)
    expect(platform.request.mock.calls.some(([request]) => request.url.includes('/cars/7/drives'))).toBe(true)
    expect(JSON.stringify(page.toJSON())).not.toContain('FIX_REQUIRED')
    if (kind === 'stats' || kind === 'efficiency') expect(page.root.findByProps({ ariaLabel: '平均能耗 -5 kWh/100km' })).toBeDefined()
  })

  it('uses App quality eligibility for efficiency without removing raw mileage history', async () => {
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car] })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      return respond({ drives: [raw(1), { ...raw(2, -500), quality_state: 'incomplete', quality_reason: 'missing_api_evidence' }], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('efficiency')
    expect(page.root.findByProps({ ariaLabel: '平均能耗 -5 kWh/100km' })).toBeDefined()
    expect(page.root.findByProps({ ariaLabel: '合格行程 1 次' })).toBeDefined()
    expect(page.root.findByProps({ className: 'analysis-coverage' }).findAllByType('text').map(node => node.children.join('')).join(' ')).toContain('已加载行程 2 条')
    const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
    const cached = readHistoryCache<{ id: string }>({ getStorageSync: (key: string) => platform.values.get(key), setStorageSync() {} }, historyCacheKey('drives', 'analysis-user', 'analysis-car', 'https://api.example.test'))
    expect(cached).toHaveLength(2)
  })

  it('drills from annual mileage to month calendar, an actual day and a concrete drive', async () => {
    const page = await mount('mileage')
    await click(page, '2026 年')
    await click(page, '2026-10')
    const day = page.root.findAllByProps({ className: 'calendar-day' }).find(node => node.props.ariaLabel?.startsWith('2026-10-14'))!
    expect(day).toBeDefined()
    await act(async () => { day.props.onClick() }); await flush()
    expect(page.root.findAllByType('text').some(node => node.children.join('') === '2026-10-14 行程')).toBe(true)
    await click(page, '查看行程 1')
    expect(platform.navigateTo).toHaveBeenCalledWith({ url: '/pages/analysis/index?kind=curves&id=1' })
  })

  it('reconstructs scoped cached analyses offline and rejects a delayed former owner', async () => {
    let page = await mount('stats')
    await act(async () => { platform.hide(); renderer!.unmount() }); renderer = null
    platform.request.mockRejectedValue(new Error('offline'))
    page = await mount('stats')
    expect(JSON.stringify(page.toJSON())).toContain('本机缓存 · 部分覆盖')
    expect(page.root.findByProps({ ariaLabel: '平均能耗 -5 kWh/100km' })).toBeDefined()
    await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null
    let release: ((value: unknown) => void) | null = null
    platform.request.mockImplementation(async () => new Promise(resolve => { release = resolve }))
    page = await mount('stats')
    await act(async () => { platform.hide() })
    await act(async () => { release!(respond({ cars: [car] })) }); await flush()
    expect(platform.request.mock.calls.filter(([request]) => request.url.includes('/drives'))).toHaveLength(1)
  })
  it('keeps the newly selected vehicle after a delayed previous vehicle analysis response', async () => {
    let release: ((value: unknown) => void) | null = null
    platform.request.mockImplementation(async ({ url }: { url: string }) => {
      if (url.endsWith('/cars')) return respond({ cars: [car, { car_id: 8, vehicle_uid: 'analysis-b', name: 'Second car' }] })
      if (url.includes('/charges')) return respond({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } })
      if (url.includes('/cars/7/drives')) return new Promise(resolve => { release = resolve })
      return respond({ drives: [{ ...raw(8), start_address: 'Current vehicle route' }], meta: { page: 1, show: 20, total_pages: 1 } })
    })
    const page = await mount('timeline')
    const picker = page.root.findAllByType('picker' as React.ElementType).find(node => node.props.range?.includes('Second car'))!
    await act(async () => { picker.props.onChange({ detail: { value: 1 } }) }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('Current vehicle route')
    await act(async () => { release!(respond({ drives: [{ ...raw(1), start_address: 'Stale vehicle route' }], meta: { page: 1, show: 20, total_pages: 1 } })) }); await flush()
    expect(JSON.stringify(page.toJSON())).toContain('Current vehicle route')
    expect(JSON.stringify(page.toJSON())).not.toContain('Stale vehicle route')
  })
})
