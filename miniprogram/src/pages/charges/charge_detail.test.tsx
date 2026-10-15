import React from 'react'
import renderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), navigateTo: vi.fn(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Input: 'input', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => { platform.values.set(key, JSON.parse(JSON.stringify(value))) },
  removeStorageSync: (key: string) => { platform.values.delete(key) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }), navigateTo: platform.navigateTo,
}, useDidShow: (callback: () => void) => { platform.show = callback }, useDidHide: (callback: () => void) => { platform.hide = callback }, useUnload: (callback: () => void) => { platform.unload = callback } }))
let tree: ReactTestRenderer | null = null
const start = '2026-10-14T00:00:00Z', end = '2026-10-14T00:30:00Z', origin = 'https://api.example.test'
const metric = (value: number, point: string) => ({ value_kwh: value, unit: 'kWh', method: 'session_counter_delta', measurement_point: point, source: 'telemetry_mqtt', quality: 'reported', start_date: start, end_date: end, coverage_kind: 'endpoints', coverage_ratio: 1 })
const charge = { charge_id: 1, session_id: 'canonical-charge', start_date: start, end_date: end, source: 'telemetry_mqtt', quality_state: 'observed', duration_min: 30,
  cost: 0, currency_code: 'EUR', charger_power: 0, charger_voltage: 230, charger_actual_current: 0, outside_temp_avg: 21,
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
it('renders actual parsed charge detail in App section order, real sample extrema/zero and working five-curve entry', async () => {
  await mount(); await detail()
  const titles = tree!.root.findAllByProps({ className: 'section-title' }).map(node => node.children.join(''))
  const startIndex = titles.indexOf('充电详情')
  expect(titles.slice(startIndex)).toEqual(['充电详情', '电量', '电池', '功率', '电压与电流', '温度', '费用'])
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
  expect(row.findByProps({ ariaLabel: '费用 5.25 USD' })).toBeDefined()
  expect(shown()).toContain('本机手动费用')
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) } }
  const raw = readHistoryCache<{ cost: number; currencyCode: string }>(storage, historyCacheKey('charges', 'owner', 'uid7', origin))
  expect(raw[0]).toMatchObject({ cost: 0, currencyCode: 'EUR' })
  await act(async () => { platform.unload(); tree!.unmount() }); tree = null; offline = true
  await mount(); await detail()
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '费用 5.25 USD' })).toBeDefined()
  await click('编辑本机费用'); await click('清除本机费用并恢复账单')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '费用 0 EUR' })).toBeDefined()
  expect(shown()).toContain('服务端报告0费用')
})
it('keeps manual zero distinct from actual zero and rejects a missing explicit currency', async () => {
  await mount(); await detail(); await edit('0', 'USD'); await click('保存本机费用')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '费用 0 USD' })).toBeDefined()
  expect(shown()).toContain('费用来源：本机手动设置')
  await edit('10', ''); await click('保存本机费用')
  expect(shown()).toContain('费用未保存')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '费用 0 USD' })).toBeDefined()
})
it('rejects a queued old cost editor save after hide/account change even when a new editor has opened', async () => {
  await mount(); await detail(); await edit('999', 'USD')
  const oldSave = tree!.root.findAllByType('button').find(node => node.children.join('') === '保存本机费用')!.props.onClick
  await act(async () => { platform.hide(); platform.values.set('matelink.wechat.session.v1', session('other')); platform.show() }); await flush(); await detail(); await edit('2', 'EUR')
  await act(async () => { oldSave() }); await flush()
  expect([...platform.values.keys()].filter(key => key.startsWith('matelink.charge-cost.'))).toEqual([])
  await click('保存本机费用')
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '费用 2 EUR' })).toBeDefined()
})
