import React from 'react'
import renderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), navigateTo: vi.fn(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Input: 'input', Image: 'image', Map: 'map', ScrollView: 'scroll-view' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => { platform.values.set(key, JSON.parse(JSON.stringify(value))) },
  removeStorageSync: (key: string) => { platform.values.delete(key) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }), navigateTo: platform.navigateTo,
}, useDidShow: (callback: () => void) => { platform.show = callback }, useDidHide: (callback: () => void) => { platform.hide = callback }, useUnload: (callback: () => void) => { platform.unload = callback }, usePageScroll: () => {} }))
let tree: ReactTestRenderer | null = null
const start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:30:00Z', origin = 'https://api.example.test'
const metric = (value: number, point: string) => ({ value_kwh: value, unit: 'kWh', method: 'session_counter_delta', measurement_point: point, source: 'telemetry_mqtt', quality: 'reported', start_date: start, end_date: end, observed_start_at: start, observed_end_at: end, time_basis: 'collector_received_at', coverage_kind: 'endpoints', coverage_ratio: 1 })
const charge = { charge_id: 1, session_id: 'canonical-charge', start_date: start, end_date: end, source: 'telemetry_mqtt', quality_state: 'observed', duration_min: 30,
  cost: 0, currency_code: 'EUR', charge_type: 'ac', charger_power: 0, charger_voltage: 230, charger_actual_current: 0, outside_temp_avg: 21,
  battery_details: { start_battery_level: 20, end_battery_level: 80 }, energy_contract: { version: 1, charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict', battery_input: metric(8, 'battery_input'), ac_input: metric(10, 'ac_charger_input') },
  charge_details: [
    { date: start, battery_level: 20, outside_temp: 20, charger_details: { charger_power: 0, charger_voltage: 230, charger_actual_current: 0 } },
    { date: end, battery_level: 80, outside_temp: 22, charger_details: { charger_power: 10, charger_voltage: 240, charger_actual_current: 16 } },
    { date: '2026-10-14T01:00:00Z', battery_level: 100, outside_temp: 999, charger_details: { charger_power: 999 } },
  ] }
const response = (data: unknown) => ({ statusCode: 200, header: {}, data: { data } })
function session(userId: string) { return { apiOrigin: origin, userId, accessToken: `synthetic-${userId}`, refreshToken: null, expiresAt: null, linkRequired: false } }
let offline = false
beforeEach(() => {
  vi.resetModules(); platform.values.clear(); platform.request.mockReset(); platform.navigateTo.mockReset(); offline = false
  vi.stubEnv('TARO_APP_API_BASE_URL', origin); platform.values.set('matelink.wechat.session.v1', session('owner'))
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (offline) throw new Error('synthetic offline')
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7', name: '合成车辆' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge, units: { unit_of_length: 'km', unit_of_temperature: 'C' } })
    return response({ charges: [charge], meta: { page: 1, show: 20, total_pages: 1 } })
  })
})
afterEach(async () => { if (tree) { await act(async () => { platform.unload(); tree!.unmount() }); tree = null } vi.unstubAllEnvs() })
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount() {
  const Page = (await import('./index')).default
  await act(async () => { tree = renderer.create(<Page />) })
  await act(async () => { platform.show() }); await flush()
  return tree!
}
async function detail() { const row = tree!.root.findByProps({ className: 'history-record' }); await act(async () => { row.props.onClick() }); await flush() }
async function click(label: string) {
  const button = tree!.root.findAllByType('button').find(node => node.children.join('') === label)
  expect(button, label).toBeDefined(); await act(async () => { button!.props.onClick() }); await flush()
}
async function edit(amount: string, currency: string) {
  await click('编辑本机费用')
  await act(async () => { const fields = tree!.root.findAllByType('input'); fields[0].props.onInput({ detail: { value: amount } }); fields[1].props.onInput({ detail: { value: currency } }) })
}
const shown = () => tree!.root.findAllByType('text').map(node => node.children.join('')).join('\n')
it.each(['missing-mode', 'dc-mode', 'uppercase-mode', 'mismatched-basis', 'blank-basis', 'missing-observed', 'submillisecond-shift'])('keeps independently qualified energies but withholds AC efficiency for %s', async reason => {
  const altered = JSON.parse(JSON.stringify(charge))
  if (reason === 'missing-mode') delete altered.charge_type
  if (reason === 'dc-mode') altered.charge_type = 'dc'
  if (reason === 'uppercase-mode') altered.charge_type = 'AC'
  if (reason === 'mismatched-basis') altered.energy_contract.battery_input.time_basis = 'source_sample_time'
  if (reason === 'blank-basis') { altered.energy_contract.battery_input.time_basis = ' '; altered.energy_contract.ac_input.time_basis = ' ' }
  if (reason === 'missing-observed') delete altered.energy_contract.battery_input.observed_start_at
  if (reason === 'submillisecond-shift') altered.energy_contract.battery_input.observed_start_at = '2026-10-14T00:00:00.000000001Z'
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7', name: '合成车辆' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: altered })
    return response({ charges: [altered], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount(); await detail()
  const panel = tree!.root.findByProps({ className: 'history-detail-overlay' })
  expect(panel.findByProps({ ariaLabel: '电池充入 8 kWh' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: 'AC 输入 10 kWh' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: 'AC输入效率 暂无数据' })).toBeDefined()
})
it('preserves qualified zero battery input as zero percent efficiency', async () => {
  const altered = { ...charge, energy_contract: { ...charge.energy_contract, battery_input: metric(0, 'battery_input') } }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }) : url.endsWith('/current') ? response({ charge: null }) : url.endsWith('/charges/1') ? response({ charge: altered }) : response({ charges: [altered], meta: { page: 1, show: 20, total_pages: 1 } }))
  await mount(); await click('显示短充'); await detail()
  expect(tree!.root.findByProps({ className: 'history-detail-overlay' }).findByProps({ ariaLabel: '电池充入 0 kWh' })).toBeDefined()
  expect(tree!.root.findByProps({ ariaLabel: 'AC输入效率 0%' })).toBeDefined()
})
it.each([{ change: 'power', resumeOffline: false }, { change: 'window', resumeOffline: true }, { change: 'weak', resumeOffline: false }, { change: 'cost', resumeOffline: false }])('reconciles $change cached charge evidence and invalidates only superseded summary/samples (offline=$resumeOffline)', async ({ change, resumeOffline }) => {
  await mount(); await detail()
  expect(tree!.root.findByProps({ ariaLabel: '样本平均功率 5 kW' })).toBeDefined()
  await act(async () => { platform.hide() })
  const updated = { ...charge, charge_details: undefined,
    ...(change === 'power' ? { charger_power: 225 } : change === 'window' ? { end_date: '2026-10-14T01:00:00Z' } : change === 'weak' ? { energy_contract: undefined, charger_power: undefined } : { cost: 42 }) }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7', name: '合成车辆' }] })
    return response({ charges: [updated], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const { matelinkApi } = await import('../../services/api')
  const { analysisApi } = await import('../../services/analysis_api')
  const { historyCacheKey, readHistoryCache, writeHistoryCache } = await import('../../services/history_cache')
  const { mergeHistoryByKey, historyRowKey } = await import('../../domain/history')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
  const key = historyCacheKey('charges', 'owner', 'uid7', origin)
  const result = await analysisApi.getHistory((await matelinkApi.getCars())[0], 'charges')
  expect(writeHistoryCache(storage, key, mergeHistoryByKey(readHistoryCache<import('../../services/types').Charge>(storage, key), result.items, historyRowKey).items)).toBe(true)
  if (resumeOffline) platform.request.mockRejectedValue(new Error('synthetic resume offline'))
  await act(async () => { platform.show() }); await flush()
  const panel = tree!.root.findByProps({ className: 'history-detail-overlay' })
  if (change === 'power') expect(panel.findByProps({ ariaLabel: '报告功率 225 kW' })).toBeDefined()
  if (change === 'power' || change === 'window') expect(panel.findByProps({ ariaLabel: '样本平均功率 暂无数据' })).toBeDefined()
  else {
    expect(panel.findByProps({ ariaLabel: '样本平均功率 5 kW' })).toBeDefined()
    expect(panel.findByProps({ ariaLabel: '电池充入 8 kWh' })).toBeDefined()
  }
  if (change === 'cost') expect(panel.findByProps({ ariaLabel: '充电费用 42 EUR' })).toBeDefined()
})
it('renders actual parsed charge detail in App section order, real sample extrema/zero and working five-curve entry', async () => {
  await mount(); await detail()
  const titles = tree!.root.findAllByProps({ className: 'section-title' }).map(node => node.children.join(''))
  const startIndex = titles.indexOf('充电详情')
  expect(titles.slice(startIndex)).toEqual(['充电详情', '位置与费用', '电量', '电池', '功率', '电压与电流', '温度'])
  for (const label of ['AC输入效率 80%', '起始SOC 20%', '结束SOC 80%', '样本最高功率 10 kW', '样本最低功率 0 kW', '样本平均功率 5 kW', '样本平均电压 235 V', '样本平均电流 8 A', '样本平均温度 21 °C']) expect(tree!.root.findAllByProps({ ariaLabel: label }), label).toHaveLength(1)
  expect(shown()).toContain('功率 2 / 电压 2 / 电流 2 / 温度 2 点')
  await click('查看SOC、功率、电压、电流、温度曲线')
  expect(platform.navigateTo).toHaveBeenCalledWith({ url: '/pages/analysis/index?kind=chargeCurves&chargeId=1' })
  expect(platform.request.mock.calls.filter(([request]) => request.url.endsWith('/charges/1'))).toHaveLength(1)
  const serialized = JSON.stringify([...platform.values.values()])
  expect(serialized).not.toContain('charge_details'); expect(serialized).not.toContain('charger_voltage')
})
it('edits manual amount/currency through actual page, retains upstream zero, reopens offline and clears back to actual zero', async () => {
  await mount(); await detail(); await edit('5.25', 'USD'); await click('保存本机费用')
  const row = tree!.root.findByProps({ className: 'history-record' })
  expect(row.findByProps({ ariaLabel: '充电费用 5.25 USD' })).toBeDefined()
  expect(shown()).toContain('本机手动费用')
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
  const raw = readHistoryCache<{ cost: number; currencyCode: string }>(storage, historyCacheKey('charges', 'owner', 'uid7', origin))
  expect(raw[0]).toMatchObject({ cost: 0, currencyCode: 'EUR' })
  await act(async () => { platform.unload(); tree!.unmount() }); tree = null; offline = true
  await mount(); await detail()
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 5.25 USD' })).toBeDefined()
  await click('编辑本机费用'); await click('清除本机费用并恢复账单')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 0 EUR' })).toBeDefined()
  expect(shown()).toContain('服务端报告0费用')
})
it('keeps manual zero distinct from actual zero and rejects a missing explicit currency', async () => {
  await mount(); await detail(); await edit('0', 'USD'); await click('保存本机费用')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 0 USD' })).toBeDefined()
  expect(shown()).toContain('费用来源：本机手动设置')
  await edit('10', ''); await click('保存本机费用')
  expect(shown()).toContain('费用未保存')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 0 USD' })).toBeDefined()
})
it('rejects a queued old cost editor save after hide/account change even when a new editor has opened', async () => {
  await mount(); await detail(); await edit('999', 'USD')
  const oldSave = tree!.root.findAllByType('button').find(node => node.children.join('') === '保存本机费用')!.props.onClick
  await act(async () => { platform.hide(); platform.values.set('matelink.wechat.session.v1', session('other')); platform.show() }); await flush(); await detail(); await edit('2', 'EUR')
  await act(async () => { oldSave() }); await flush()
  expect([...platform.values.keys()].filter(key => key.startsWith('matelink.charge-cost.'))).toEqual([])
  await click('保存本机费用')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 2 EUR' })).toBeDefined()
})
it('shows an App-style short address, marked default-price estimate and only returned-month chart data', async () => {
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency_code: null, charge_type: null, energy_contract: null,
    charge_energy_added: 24.0175, address: '合成路, Test District, Test City', latitude: null, longitude: null, charge_details: [] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: legacy })
    return response({ charges: [legacy], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  const row = tree!.root.findByProps({ className: 'history-record' })
  expect(row.findByProps({ className: 'history-charge-location' }).findAllByType('text')[0].children.join('')).toBe('合成路')
  expect(row.findByProps({ ariaLabel: '充电费用 ≈¥27.38' })).toBeDefined()
  expect(row.findByProps({ ariaLabel: '能量 24.0 kWh' })).toBeDefined()
  const summary = tree!.root.findByProps({ className: 'card history-interval' })
  expect(summary.findByProps({ ariaLabel: '费用 ≈27.38 CNY' })).toBeDefined()
  expect(summary.findByProps({ ariaLabel: '单次均价 ≈27.38 CNY' })).toBeDefined()
  expect(shown()).toContain('费用按 App 默认 ¥1.14/kWh 估算')
  await click('费用')
  const chart = tree!.root.findByProps({ className: 'card charge-month-chart' })
  expect(chart.findAllByProps({ className: 'charge-chart-column' })).toHaveLength(1)
  expect(chart.findByProps({ className: 'charge-chart-value' }).children.join('')).toBe('¥27.38')
  expect(chart.findByProps({ className: 'muted' }).children.join('')).toContain('已返回的 1 条记录')
  await detail()
  const panel = tree!.root.findByProps({ className: 'history-detail-overlay' })
  expect(panel.findByProps({ ariaLabel: '充电费用 ≈¥27.38' })).toBeDefined()
  expect(shown()).toContain('费用来源：按 App 默认 ¥1.14/kWh 与合格电池充入量估算')
  expect(panel.findAllByType('map' as React.ElementType)).toHaveLength(0)
  expect(panel.findAllByProps({ className: 'section-title' }).map(node => node.children.join(''))).not.toContain('电压与电流')
})
it('shows a map only for a valid online detail coordinate', async () => {
  const at = { ...charge, latitude: 31.2, longitude: 121.5 }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: at })
    return response({ charges: [at], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount(); await detail()
  const map = tree!.root.findByType('map' as React.ElementType)
  expect(map.props.latitude).not.toBe(31.2)
  expect(map.props.longitude).not.toBe(121.5)
  expect(JSON.stringify([...platform.values.values()])).not.toContain('31.2')
})
it('keeps a missing CNY bill estimate separate from an actual foreign-currency zero in the summary', async () => {
  const legacy = { ...charge, charge_id: 1, session_id: 'legacy-1', source: 'teslamate_archive', cost: null, currency_code: null,
    energy_contract: null, charge_energy_added: 24.0175, charge_details: [] }
  const free = { ...charge, charge_id: 2, session_id: 'paid-2', cost: 0, currency_code: 'EUR' }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    return response({ charges: [legacy, free], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  const summary = tree!.root.findByProps({ className: 'card history-interval' })
  const summaryText = summary.findAllByType('text').map(node => node.children.join('')).join('\n')
  expect(summaryText).toContain('费用')
  expect(summaryText).not.toContain('实际/手填费用覆盖')
  expect(summary.findByProps({ ariaLabel: '费用 暂无数据' })).toBeDefined()
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
  const raw = readHistoryCache<{ cost: number | null }>(storage, historyCacheKey('charges', 'owner', 'uid7', origin))
  expect(raw.find(row => row.cost === null)).toBeDefined()
  expect(raw.find(row => row.cost === 0)).toBeDefined()
})
it.each([
  { currency: 'CNY', defaultPrice: 0, card: '充电费用 ≈¥0.00', chart: '¥0.00', source: '已保存默认电价 ¥0/kWh' },
  { currency: 'EUR', defaultPrice: 2, card: '充电费用 ≈20.00 EUR', chart: '20.00 EUR', source: '已保存默认电价 2 EUR/kWh' },
])('uses scoped saved $currency default price $defaultPrice before the App fallback, without using TOU rates', async ({ currency, defaultPrice, card, chart, source }) => {
  const { tariffKey } = await import('../../domain/tariff')
  platform.values.set(tariffKey('owner', origin), { currency, defaultPrice, enabled: true, peak: 9, flat: 8, valley: 7, timeZone: 'Asia/Shanghai' })
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency_code: null, energy_contract: null, charge_energy_added: 10, charge_details: [] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: legacy })
    return response({ charges: [legacy], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  const row = tree!.root.findByProps({ className: 'history-record' })
  expect(row.findByProps({ ariaLabel: card })).toBeDefined()
  expect(shown()).toContain(source)
  await click('费用')
  expect(tree!.root.findByProps({ className: 'charge-chart-value' }).children.join('')).toBe(chart)
  await detail()
  expect(shown()).toContain(`费用来源：按 ${source} 与合格电池充入量估算`)
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
  expect(readHistoryCache<{ cost: number | null }>(storage, historyCacheKey('charges', 'owner', 'uid7', origin))[0].cost).toBeNull()
})
it('does not adopt a default price saved by another account or origin', async () => {
  const { tariffKey } = await import('../../domain/tariff')
  const wrong = { currency: 'EUR', defaultPrice: 9, enabled: false, peak: 8, flat: 7, valley: 6 }
  platform.values.set(tariffKey('other', origin), wrong)
  platform.values.set(tariffKey('owner', 'https://elsewhere.example.test'), wrong)
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency_code: null, energy_contract: null, charge_energy_added: 10, charge_details: [] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    return response({ charges: [legacy], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 ≈¥11.40' })).toBeDefined()
  expect(shown()).toContain('费用按 App 默认 ¥1.14/kWh 估算')
})
it('shows TeslaMate unfilled raw zero as an estimate until the owner explicitly saves manual zero', async () => {
  const legacy = { ...charge, source: 'teslamate_archive', cost: 0, currency_code: 'CNY', energy_contract: null,
    charge_energy_added: 10, charge_details: [] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: legacy })
    return response({ charges: [legacy], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 ≈¥11.40' })).toBeDefined()
  expect(shown()).toContain('费用按 App 默认 ¥1.14/kWh 估算')
  await detail()
  expect(shown()).toContain('费用来源：按 App 默认 ¥1.14/kWh 与合格电池充入量估算')
  await edit('0', 'CNY'); await click('保存本机费用')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 0 CNY' })).toBeDefined()
  expect(shown()).toContain('费用来源：本机手动设置')
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
  expect(readHistoryCache<{ cost: number }>(storage, historyCacheKey('charges', 'owner', 'uid7', origin))[0].cost).toBe(0)
})
