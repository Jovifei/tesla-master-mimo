import React from 'react'
import renderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), params: { kind: 'battery' } as Record<string, string>,
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void, switchTab: vi.fn(), navigateTo: vi.fn(), modal: vi.fn() }))
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view', Button: 'button', Picker: 'picker', Input: 'input', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => { platform.values.set(key, JSON.parse(JSON.stringify(value))) },
  removeStorageSync: (key: string) => { platform.values.delete(key) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }), switchTab: platform.switchTab, navigateTo: platform.navigateTo, showModal: platform.modal,
}, useRouter: () => ({ params: platform.params }), useDidShow: (callback: () => void) => { platform.show = callback }, useDidHide: (callback: () => void) => { platform.hide = callback }, useUnload: (callback: () => void) => { platform.unload = callback } }))
let tree: ReactTestRenderer | null = null
const start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:30:00Z'
const rawDrive = (id: number, value: number | null = 2, km = 10) => ({ drive_id: id, start_date: start, end_date: end, source: 'telemetry_mqtt', quality_state: 'observed', duration_min: 30,
  odometer_details: { odometer_distance: km }, range_rated: { start_range: 100, end_range: 80 }, speed_avg: 60, outside_temp_avg: 20,
  energy_contract: { version: 1, net_energy: { value_kwh: value, unit: 'kWh', source: 'telemetry_mqtt', quality: value == null ? 'unknown' : 'reported', method: 'api_reported_net', measurement_point: 'reported_net', start_date: start, end_date: end } } })
const rawCharge = (id: number, cost: number | null, currency: string | null) => ({ charge_id: id, start_date: start, end_date: end, source: 'telemetry_mqtt', quality_state: 'observed', cost, currency_code: currency,
  charger_power: 0, duration_min: 30, charge_energy_added: 2, energy_contract: { version: 1, battery_input: { value_kwh: 2, unit: 'kWh', source: 'telemetry_mqtt', quality: 'reported', method: 'session_counter_delta', measurement_point: 'battery_input', start_date: start, end_date: end, coverage_kind: 'endpoints', coverage_ratio: 1 } } })
let drives = [rawDrive(1), rawDrive(2, null)], charges = [rawCharge(1, 0, 'EUR'), rawCharge(2, 5, 'USD'), rawCharge(3, 8, null)]
let health: unknown = null, healthMeta: unknown = { availability: 'unsupported', source: 'fleet_telemetry_native' }, current: unknown = null
const response = (data: unknown) => ({ statusCode: 200, header: {}, data: { data } })
beforeEach(() => {
  vi.resetModules(); platform.values.clear(); platform.request.mockReset(); platform.switchTab.mockReset(); platform.navigateTo.mockReset(); platform.modal.mockResolvedValue({ confirm: true })
  platform.params = { kind: 'battery' }; drives = [rawDrive(1), rawDrive(2, null)]; charges = [rawCharge(1, 0, 'EUR'), rawCharge(2, 5, 'USD'), rawCharge(3, 8, null)]
  health = null; healthMeta = { availability: 'unsupported', source: 'fleet_telemetry_native' }; current = null
  vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-15T00:00:00Z'))
  platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', userId: 'owner', accessToken: 'synthetic', refreshToken: null, expiresAt: null, linkRequired: false })
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7', name: '合成车辆' }] })
    if (url.includes('/battery-health')) return response({ battery_health: health, meta: healthMeta })
    if (url.endsWith('/status')) return response({ status: { battery_details: { battery_level: 99, rated_battery_range: 123 }, driving_details: { speed: 60 }, climate_details: { outside_temp: 20 } }, units: { unit_of_length: 'km', unit_of_temperature: 'C' }, observed_at: start, source: 'telemetry_mqtt' })
    if (url.includes('/charges/current')) return response({ charge: current })
    if (url.includes('/drives?')) return response({ drives, units: { unit_of_length: 'km', unit_of_temperature: 'C' }, meta: { page: 1, show: 20, total: drives.length, total_pages: 1 } })
    if (url.includes('/charges?')) return response({ charges, meta: { page: 1, show: 20, total: charges.length, total_pages: 1 } })
    throw new Error('unexpected synthetic route')
  })
})
afterEach(async () => {
  if (tree) { await act(async () => { platform.unload(); tree!.unmount() }); tree = null }
  vi.restoreAllMocks(); vi.unstubAllEnvs()
})
async function mount(kind: string) {
  platform.params = { kind }
  const Page = (await import('./index')).default
  await act(async () => { tree = renderer.create(<Page />) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  return tree!
}
const shown = () => tree!.root.findAllByType('text').map(node => node.children.join('')).join('\n')
async function click(label: string) {
  const button = tree!.root.findAllByType('button').find(node => node.children.join('') === label)
  expect(button, label).toBeDefined()
  await act(async () => { button!.props.onClick(); await new Promise(resolve => setTimeout(resolve, 0)) })
}
it('actual battery endpoint unsupported response keeps capacity unknown despite current SOC 99', async () => {
  await mount('battery')
  expect(shown()).toContain('此数据源尚未提供电池健康合同')
  expect(tree!.root.findAllByProps({ className: 'metric' }).find(node => node.props.ariaLabel.startsWith('当前容量'))!.props.ariaLabel).toContain('暂无数据')
  expect(tree!.root.findAllByProps({ className: 'metric' }).find(node => node.props.ariaLabel.startsWith('当前报告续航'))!.props.ariaLabel).toContain('123 km')
})
it('actual parsed charge histories render separate currencies and genuine zero without a default currency', async () => {
  await mount('cost')
  const rendered = shown()
  expect(rendered).toContain('EUR 费用'); expect(rendered).toContain('USD 费用'); expect(rendered).toContain('币种未知 1 次')
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '费用合计 0 EUR')).toBe(true)
  expect(rendered).not.toContain('CNY')
  expect(rendered).toContain('分页读取完成')
})
it('actual range proof and capacity response reach personalized range and rated endpoint consumers', async () => {
  drives = Array.from({ length: 5 }, (_, i) => rawDrive(i + 1, 4, 20))
  health = { current_capacity: 60, max_capacity: 65, max_range: 450, current_range: 420, battery_health_percentage: 92 }
  healthMeta = { availability: 'available', source: 'teslamate', quality_state: 'observed', observed_at: start }
  await mount('range')
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '合格能耗与容量估算 300 km')).toBe(true)
  expect(shown()).toContain('当前条件分组 · 5 段')
  expect(shown()).toContain('共 5 段')
  await click('今天')
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '合格能耗与容量估算 300 km')).toBe(true)
  expect(shown()).toContain('共 0 段')
})
it('excludes incomplete history from App range and cost analysis while keeping source receipts', async () => {
  drives = [...Array.from({ length: 5 }, (_, i) => rawDrive(i + 1, 4, 20)), { ...rawDrive(6, 300, 20), quality_state: 'incomplete' }]
  health = { current_capacity: 60, max_capacity: 65, max_range: 450, current_range: 420, battery_health_percentage: 92 }
  healthMeta = { availability: 'available', source: 'teslamate', quality_state: 'observed', observed_at: start }
  await mount('range')
  expect(tree!.root.findByProps({ ariaLabel: '合格能耗与容量估算 300 km' })).toBeDefined()
  expect(shown()).toContain('当前条件分组 · 5 段')
  expect(shown()).toContain('行驶已加载 6 条')
  await act(async () => { platform.unload(); tree!.unmount() }); tree = null

  charges = [rawCharge(1, 0, 'EUR'), { ...rawCharge(2, 500, 'EUR'), quality_state: 'incomplete' }]
  await mount('cost')
  expect(tree!.root.findByProps({ ariaLabel: '费用合计 0 EUR' })).toBeDefined()
  expect(shown()).toContain('已加载 1 次')
  expect(shown()).toContain('充电已加载 2 条')
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  const cached = readHistoryCache<{ id: string }>({ getStorageSync: (key: string) => platform.values.get(key), setStorageSync() {} }, historyCacheKey('charges', 'owner', 'uid7', 'https://api.example.test'))
  expect(cached).toHaveLength(2)
})
it('actual manual selection saves a scoped bounded trip and rebuilds detail offline without inventing energy for missing leg', async () => {
  await mount('longTrips'); await click('创建长途行程')
  await act(async () => { tree!.root.findByType('input').props.onInput({ detail: { value: '合成手动长途' } }) })
  for (let i = 0; i < 2; i++) {
    const button = tree!.root.findAllByType('button').find(node => node.children.join('').startsWith('选择 行驶'))!
    await act(async () => { button.props.onClick() })
  }
  await click('保存本机长途行程')
  expect(shown()).toContain('合成手动长途')
  expect(shown()).toContain('能耗配对 1/2')
  await click('查看原始行驶详情')
  expect(platform.navigateTo).toHaveBeenCalledWith({ url: '/pages/analysis/index?kind=curves&id=1' })
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '配对平均能耗 20 kWh/100km')).toBe(true)
  expect([...platform.values.keys()].some(key => key.startsWith('matelink.manual-trips.v1.'))).toBe(true)
  await act(async () => { platform.unload(); tree!.unmount() }); tree = null
  platform.request.mockRejectedValue(new Error('synthetic offline'))
  await mount('longTrips')
  expect(shown()).toContain('合成手动长途')
  expect(shown()).toContain('本机缓存')
  const row = tree!.root.findAllByProps({ className: 'card' }).find(node => node.props.onClick)
  await act(async () => { row!.props.onClick() })
  expect(shown()).toContain('行程时间线')
  expect(shown()).toContain('能耗配对 1/2')
})
it('reads actual current-charge endpoint with genuine zero power and routes to charge history', async () => {
  current = rawCharge(1, 0, 'EUR')
  await mount('currentCharge')
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '当前充电功率 0 kW')).toBe(true)
  await click('查看充电历史')
  expect(platform.switchTab).toHaveBeenCalledWith({ url: '/pages/charges/index' })
})
it('refuses a late range response after hide and account change, without rendering or persisting it', async () => {
  let finish: (value: unknown) => void = () => {}
  const normal = platform.request.getMockImplementation()!
  platform.request.mockImplementation((options: { url: string }) => options.url.includes('/drives?') ? new Promise(resolve => { finish = resolve }) : normal(options))
  await mount('range')
  await act(async () => { platform.hide(); platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', userId: 'other', accessToken: 'synthetic-other', expiresAt: null }) })
  await act(async () => { finish(response({ drives, meta: { page: 1, show: 20, total_pages: 1 } })); await new Promise(resolve => setTimeout(resolve, 0)) })
  expect([...platform.values.keys()].some(key => key.startsWith('matelink.history.'))).toBe(false)
  expect(shown()).not.toContain('共 2 段')
})
it('keeps actual zero bills and labels the separate saved tariff estimate only for a missing bill', async () => {
  const proof = { version: 1, charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict', ac_input: {
    value_kwh: 2, unit: 'kWh', source: 'telemetry_mqtt', quality: 'reported', method: 'session_counter_delta', measurement_point: 'ac_charger_input', start_date: start, end_date: end, coverage_kind: 'endpoints', coverage_ratio: 1 } }
  charges = [rawCharge(1, 0, 'EUR'), rawCharge(2, null, 'EUR')].map(row => ({ ...row, energy_contract: { ...row.energy_contract, ...proof } }))
  platform.values.set('matelink.tariff.v1.https%3A%2F%2Fapi%2Eexample%2Etest.owner', { currency: 'EUR', peak: 1, flat: 1, valley: 1, timeZone: 'Asia/Shanghai' })
  await mount('cost')
  expect(tree!.root.findAllByProps({ className: 'metric' }).filter(node => node.props.ariaLabel === '费率估算 2 EUR')).toHaveLength(1)
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '费用合计 0 EUR')).toBe(true)
  expect(shown()).toContain('不写回实际费用')
})
it('edits and explicitly deletes one saved manual trip while preserving original scoped drive history', async () => {
  await mount('longTrips'); await click('创建长途行程')
  await act(async () => { tree!.root.findByType('input').props.onInput({ detail: { value: '原名称' } }) })
  const leg = tree!.root.findAllByType('button').find(node => node.children.join('').startsWith('选择 行驶'))!
  await act(async () => { leg.props.onClick() }); await click('保存本机长途行程'); await click('编辑长途行程')
  await act(async () => { tree!.root.findByType('input').props.onInput({ detail: { value: '修改名称' } }) })
  await click('保存本机长途行程')
  expect(shown()).toContain('修改名称')
  const historyBefore = [...platform.values.entries()].filter(([key]) => key.startsWith('matelink.history.'))
  await click('删除本机长途行程')
  expect(platform.modal).toHaveBeenCalledWith(expect.objectContaining({ title: '删除本机长途行程' }))
  expect(shown()).toContain('尚无手动长途行程')
  expect([...platform.values.entries()].filter(([key]) => key.startsWith('matelink.history.'))).toEqual(historyBefore)
})
it('clears previous account insight immediately before a delayed new account car lookup', async () => {
  await mount('cost'); expect(shown()).toContain('EUR 费用')
  let finish: (value: unknown) => void = () => {}
  platform.request.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', userId: 'other', accessToken: 'synthetic-other', expiresAt: null })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  expect(shown()).not.toContain('EUR 费用')
  expect(tree!.root.findAllByProps({ className: 'metric' })).toHaveLength(0)
  await act(async () => { finish(response({ cars: [] })); await new Promise(resolve => setTimeout(resolve, 0)) })
})
it('uses the same manual cost overlay in analysis while preserving the raw parsed charge cache', async () => {
  const { normalizeCharge } = await import('../../services/api'), { writeChargeCostOverride } = await import('../../services/charge_cost')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, removeStorageSync: (key: string) => { platform.values.delete(key) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
  expect(writeChargeCostOverride(storage, { userId: 'owner', origin: 'https://api.example.test', stableId: 'uid7' }, normalizeCharge(charges[0])!, 7, 'USD', () => true)).toBe(true)
  await mount('cost')
  expect(shown()).toContain('含 1 条本机手动费用')
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '费用合计 12 USD')).toBe(true)
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  expect(readHistoryCache<{ cost: number; currencyCode: string }>(storage, historyCacheKey('charges', 'owner', 'uid7', 'https://api.example.test'))[0]).toMatchObject({ cost: 0, currencyCode: 'EUR' })
})
it('shows manual price in selected trip previews/details while the saved leg retains its upstream bill', async () => {
  const { normalizeCharge } = await import('../../services/api'), { writeChargeCostOverride } = await import('../../services/charge_cost')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, removeStorageSync: (key: string) => { platform.values.delete(key) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
  expect(writeChargeCostOverride(storage, { userId: 'owner', origin: 'https://api.example.test', stableId: 'uid7' }, normalizeCharge(charges[0])!, 7, 'USD', () => true)).toBe(true)
  await mount('longTrips'); await click('创建长途行程')
  await act(async () => { tree!.root.findByType('input').props.onInput({ detail: { value: '手动价格组合' } }) })
  const drive = tree!.root.findAllByType('button').find(node => node.children.join('').startsWith('选择 行驶'))!, charge = tree!.root.findAllByType('button').find(node => node.children.join('').startsWith('选择 充电') && node.children.join('').includes('7 USD'))!
  expect(charge).toBeDefined()
  await act(async () => { drive.props.onClick(); charge.props.onClick() }); await click('保存本机长途行程')
  expect(tree!.root.findAllByProps({ className: 'metric' }).some(node => node.props.ariaLabel === '段费用 7 USD')).toBe(true)
  expect(shown()).toContain('包含本机手动费用')
  const { readManualTrips } = await import('../../domain/insights')
  const saved = readManualTrips(storage, { userId: 'owner', origin: 'https://api.example.test', stableId: 'uid7' })[0]
  const raw = saved.legs.find(leg => leg.kind === 'charge')
  expect(raw && raw.kind === 'charge' ? raw.charge : null).toMatchObject({ cost: 0, currencyCode: 'EUR' })
})
