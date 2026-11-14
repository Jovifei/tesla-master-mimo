import React from 'react'
import renderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { historyCacheKey } from '../../services/history_cache'
const platform = vi.hoisted(() => ({ request: vi.fn(), login: vi.fn(), values: new Map<string, unknown>(), navigateTo: vi.fn(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Input: 'input', Image: 'image', Map: 'map', ScrollView: 'scroll-view', Canvas: 'canvas', Slider: 'slider' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, login: platform.login, getStorageSync: (key: string) => platform.values.get(key),
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
  vi.resetModules(); platform.values.clear(); platform.request.mockReset(); platform.login.mockReset(); platform.navigateTo.mockReset(); offline = false
  vi.stubEnv('TARO_APP_API_BASE_URL', origin); platform.values.set('matelink.wechat.session.v1', session('owner'))
  // The fixed October fixture is not intended to test the default date range.
  platform.values.set(`${historyCacheKey('charges', 'owner', 'uid7', origin)}.query`, { startDate: '', endDate: '', minDistanceKm: 0 })
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
it('groups visible charges by their actual local day and keeps unknown dates last', async () => {
  const older = { ...charge, charge_id: 2, session_id: 'older-charge', start_date: '2026-10-11T12:00:00Z' }
  const undated = { ...charge, charge_id: 3, session_id: 'undated-charge', start_date: null }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars')
    ? response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }) : url.endsWith('/current') ? response({ charge: null })
      : response({ charges: [older, undated, charge], meta: { page: 1, show: 20, total_pages: 1 } }))
  await mount()
  const groups = tree!.root.findAllByProps({ className: 'charge-history-day' })
  expect(groups).toHaveLength(3)
  expect(groups.map(group => group.findAllByProps({ className: 'history-record' }).length)).toEqual([1, 1, 1])
  const { civilDay, dayCaption } = await import('../../domain/ui_presentation')
  expect(groups.map(group => group.findByProps({ className: 'charge-history-day-title' }).children.join('')))
    .toEqual([dayCaption(civilDay(charge.start_date)!), dayCaption(civilDay(older.start_date)!), '日期待确认'])
  expect(shown()).toContain('另有 1 条记录日期无法确认')
})
it('real charge detail keeps its original observed-quality wording', async () => {
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'false')
  await mount(); await detail()
  expect(shown()).toContain('记录质量：真实记录')
  expect(shown()).not.toContain('合成观测')
  expect(tree!.root.findByProps({ ariaLabel: '充电详情' }).findAllByProps({ className: 'card fixture-data-notice' })).toHaveLength(0)
})
it('warns only when an ambiguous cached charge is actually loaded and keeps it out of the list and totals', async () => {
  const warning = '来源身份未确认的历史记录已保留，但未计入当前展示或统计'
  await mount()
  expect(shown()).not.toContain(warning)
  await act(async () => { platform.hide() })
  const { historyCacheKey, readHistoryCache } = await import('../../services/history_cache')
  const key = historyCacheKey('charges', 'owner', 'uid7', origin)
  const cached = platform.values.get(key) as { items: Record<string, unknown>[] }
  cached.items.push({ ...cached.items[0], id: 'ambiguous', sessionId: 'ambiguous', identityAmbiguous: true, identityAmbiguousReason: 'source_identity_incomplete', energyAddedKwh: 200 })
  offline = true
  await act(async () => { platform.show() }); await flush()
  expect(shown()).toContain(warning)
  expect(tree!.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
  expect(tree!.root.findByProps({ ariaLabel: '充电次数 1 次' })).toBeDefined()
  const storage = { getStorageSync: (name: string) => platform.values.get(name), setStorageSync() {} }
  expect(readHistoryCache<Record<string, unknown>>(storage, key)).toHaveLength(2)
})
it('does not call an entirely ambiguous cache a set of short charges', async () => {
  await mount()
  await act(async () => { platform.hide() })
  const { historyCacheKey } = await import('../../services/history_cache')
  const cached = platform.values.get(historyCacheKey('charges', 'owner', 'uid7', origin)) as { items: Record<string, unknown>[] }
  cached.items[0].identityAmbiguous = true
  offline = true
  await act(async () => { platform.show() }); await flush()
  expect(shown()).toContain('来源身份未确认的历史记录已保留，但未计入当前展示或统计')
  expect(shown()).not.toContain('当前记录均为短充')
  expect(tree!.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
})
it('applies the short-charge toggle to the same rows in the list, summary and chart', async () => {
  const short = { ...charge, charge_id: 2, session_id: 'short', cost: 12, currency_code: 'CNY',
    energy_contract: { ...charge.energy_contract, battery_input: metric(.05, 'battery_input'), ac_input: metric(.06, 'ac_charger_input') } }
  const normal = { ...charge, cost: 12, currency_code: 'CNY' }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars')
    ? response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }) : url.endsWith('/current') ? response({ charge: null })
      : response({ charges: [normal, short], meta: { page: 1, show: 20, total_pages: 1 } }))
  await mount()
  expect(tree!.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
  expect(tree!.root.findByProps({ ariaLabel: '充电次数 1 次' })).toBeDefined()
  expect(shown()).toContain('统计已返回的 1 条记录')
  await click('显示短充')
  expect(tree!.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(tree!.root.findByProps({ ariaLabel: '充电次数 2 次' })).toBeDefined()
  expect(shown()).toContain('统计已返回的 2 条记录')
})
it('does not render a stopped current charge while the poll is still pending', async () => {
  let count = 0, release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }))
    if (url.endsWith('/current')) return ++count === 1
      ? Promise.resolve(response({ car: { car_id: 7 }, charge: { ...charge, is_charging: false } }))
      : new Promise(resolve => { release = resolve })
    if (url.endsWith('/snapshot')) return Promise.resolve(response({ status: { charging_details: { charging_state: 'Stopped' } } }))
    return Promise.resolve(response({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  await mount()
  expect(shown()).not.toContain('正在充电')
  if (release) { await act(async () => { release(response({ car: { car_id: 7 }, charge: null })) }); await flush() }
})
it('does not call a failed current-charge request an unmaterialized session', async () => {
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }))
    if (url.endsWith('/current')) return Promise.reject(new Error('network unavailable'))
    if (url.endsWith('/snapshot')) return Promise.resolve(response({ status: { charging_details: { charging_state: 'Charging' } } }))
    return Promise.resolve(response({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  await mount(); await flush()
  expect(shown()).not.toContain('充电即将开始')
  expect(shown()).toContain('网络暂时不可用')
})
it('renders a qualified live current-charge snapshot without inventing its start time', async () => {
  const live = { charge_id: 7, source: 'fleet_api', quality_state: 'observed', quality_reason: 'live_provider_snapshot',
    start_date: start, is_charging: true, charge_energy_added: 5, charger_power: 0, battery_details: { current_battery_level: 66 } }
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }))
    if (url.endsWith('/current')) return Promise.resolve(response({ car: { car_id: 7 }, charge: live }))
    if (url.endsWith('/snapshot')) return Promise.resolve(response({ status: { charging_details: { charging_state: 'Charging' } } }))
    return Promise.resolve(response({ charges: [], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  await mount(); await flush()
  expect(tree!.root.findByProps({ ariaLabel: '已充入 5 kWh' })).toBeDefined()
  expect(tree!.root.findByProps({ ariaLabel: '当前电量 66%' })).toBeDefined()
  expect(shown()).toContain('状态观测')
  expect(shown()).not.toContain('开始：2026-10-14')
})
it('does not display charge samples from a different source instance on the same public ID', async () => {
  const list = { ...charge, source_instance_id: 'archive-a', source_record_id: 'record-1' }
  const foreign = { ...list, source_instance_id: 'archive-b', charger_power: 999,
    charge_details: [{ date: start, charger_details: { charger_power: 999 } }] }
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }))
    if (url.endsWith('/current')) return Promise.resolve(response({ charge: null }))
    if (url.endsWith('/charges/1')) return Promise.resolve(response({ charge: foreign }))
    return Promise.resolve(response({ charges: [list], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  await mount(); await detail()
  expect(shown()).not.toContain('999')
})
it('hides the previous account charge records while a replacement account refresh is pending', async () => {
  await mount()
  expect(tree!.root.findAllByProps({ className: 'history-record' }).length).toBeGreaterThan(0)
  await act(async () => { platform.hide() })
  platform.values.set('matelink.wechat.session.v1', { ...session('other'), refreshToken: 'refresh-other', expiresAt: '2000-01-01T00:00:00Z' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/v1/session/refresh')
    ? new Promise(resolve => { release = resolve }) : Promise.resolve(response({ cars: [] })))
  await act(async () => { platform.show() }); await flush()
  expect(tree!.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
  await act(async () => { release({ statusCode: 200, data: { access_token: 'fresh-other', refresh_token: 'next' }, header: {} }) }); await flush()
})
it('clears old charge records after expired refresh rejection until the shared recovery completes', async () => {
  const storage = await import('../../services/session')
  storage.writeWechatRecoveryPreference({ setStorageSync: (name, value) => platform.values.set(name, value) },
    { apiOrigin: origin, boundUserId: 'owner', enabled: true })
  platform.values.set('matelink.wechat.session.v1', { ...session('owner'), refreshToken: 'refresh-owner' })
  await mount()
  expect(tree!.root.findAllByProps({ className: 'history-record' }).length).toBeGreaterThan(0)
  await act(async () => { platform.hide() })
  platform.values.set('matelink.wechat.session.v1', { ...session('owner'), refreshToken: 'refresh-owner', expiresAt: '2000-01-01T00:00:00Z' })
  platform.login.mockResolvedValue({ code: 'fresh-code' })
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/v1/session/refresh')) return Promise.resolve({ statusCode: 401, data: { error: 'session_required' }, header: {} })
    if (url.endsWith('/v1/auth/wechat/restore')) return new Promise(resolve => { release = resolve })
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [{ car_id: 7, vehicle_uid: 'uid7', name: '合成车辆' }] }))
    if (url.endsWith('/current')) return Promise.resolve(response({ charge: null }))
    return Promise.resolve(response({ charges: [charge], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  await act(async () => { platform.show() })
  await vi.waitFor(() => expect(platform.request.mock.calls.some(([request]) => request.url.endsWith('/v1/auth/wechat/restore'))).toBe(true))
  expect(tree!.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
  await act(async () => { release({ statusCode: 200, data: { access_token: 'restored', user: { id: 'owner' } }, header: {} }) }); await flush()
  expect(tree!.root.findAllByProps({ className: 'history-record' }).length).toBeGreaterThan(0)
  expect(platform.login).toHaveBeenCalledTimes(1)
  const lastCars = platform.request.mock.calls.filter(([request]) => request.url.endsWith('/cars')).slice(-1)[0][0]
  expect(lastCars.header.Authorization).toBe('Bearer restored')
})
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
  expect(panel.findByProps({ ariaLabel: `${reason === 'dc-mode' ? '输入电量（模式未确认）' : 'AC 输入'} 10 kWh` })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: 'AC输入效率 暂无数据' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '样本平均电压 235 V' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '样本最低电流 0 A' })).toBeDefined()
})
it('shows a normalized AC/fast-charge conflict as unknown without losing reported energy or bill', async () => {
  const conflicting = { ...charge, fast_charger_present: true }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: conflicting })
    return response({ charges: [conflicting], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  expect(tree!.root.findByProps({ className: 'history-charge-mode history-charge-mode-unknown' }).children.join('')).toBe('模式未确认')
  await detail()
  const panel = tree!.root.findByProps({ className: 'history-detail-overlay' })
  expect(shown()).toContain('模式未确认')
  expect(panel.findByProps({ ariaLabel: '电池充入 8 kWh' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '输入电量（模式未确认） 10 kWh' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '充电费用 0 EUR' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: 'AC输入效率 暂无数据' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '样本平均电压 235 V' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '样本平均电流 8 A' })).toBeDefined()
})
it('treats a malformed conflict marker in the reopened offline cache as unknown mode', async () => {
  await mount()
  await act(async () => { platform.hide() })
  const { historyCacheKey } = await import('../../services/history_cache')
  const cached = platform.values.get(historyCacheKey('charges', 'owner', 'uid7', origin)) as { items: Record<string, unknown>[] }
  expect(cached.items).toHaveLength(1)
  cached.items[0].chargeModeConflict = 'true'
  offline = true
  await act(async () => { platform.show() }); await flush()
  expect(tree!.root.findAllByProps({ className: 'history-charge-mode' })).toHaveLength(0)
  await detail()
  const panel = tree!.root.findByProps({ className: 'history-detail-overlay' })
  expect(shown()).toContain('模式未确认')
  expect(panel.findByProps({ ariaLabel: '输入电量（模式未确认） 10 kWh' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '充电费用 0 EUR' })).toBeDefined()
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
it('renders actual parsed charge detail in App section order, real sample extrema/zero and inline five curves', async () => {
  await mount(); await detail()
  const titles = tree!.root.findAllByProps({ className: 'section-title' }).map(node => node.children.join(''))
  const startIndex = titles.indexOf('充电详情')
  expect(titles.slice(startIndex)).toEqual(['充电详情', '充电概览', '电量', '电池', '功率', '电压与电流', '温度', '充电电量变化', '充电功率', '充电电压', '充电电流', '环境温度'])
  for (const label of ['AC输入效率 80%', '起始SOC 20%', '结束SOC 80%', '样本最高功率 10 kW', '样本最低功率 0 kW', '样本平均功率 5 kW', '样本平均电压 235 V', '样本平均电流 8 A', '样本平均温度 21 °C']) expect(tree!.root.findAllByProps({ ariaLabel: label }), label).toHaveLength(1)
  expect(shown()).toContain('缺失数据保持未知')
  // App parity: the five curves are inline in the detail; there is no
  // jump-to-analysis entry anymore.
  expect(tree!.root.findAllByType('button').some(node => node.children.join('') === '查看SOC、功率、电压、电流、温度曲线')).toBe(false)
  expect(platform.navigateTo.mock.calls.some(([arg]) => String((arg as { url?: string })?.url ?? '').includes('chargeCurves'))).toBe(false)
  for (const id of ['charge-detail-soc', 'charge-detail-power', 'charge-detail-voltage', 'charge-detail-current', 'charge-detail-temperature']) expect(tree!.root.findByProps({ id }), id).toBeDefined()
  expect(platform.request.mock.calls.filter(([request]) => request.url.endsWith('/charges/1'))).toHaveLength(1)
  const serialized = JSON.stringify([...platform.values.values()])
  expect(serialized).not.toContain('charge_details'); expect(serialized).not.toContain('charger_voltage')
})
it('does not expose manual cost editing to users, keeping read-only accounting detail', async () => {
  await mount(); await detail()
  expect(tree!.root.findAllByType('button').some(node => node.children.join('') === '编辑本机费用')).toBe(false)
  expect(shown()).not.toContain('本机手动费用')
})
it('shows an App-style short address, marked default-price estimate and only returned-day cell data', async () => {
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency_code: null, charge_type: null, energy_contract: null,
    charge_energy_added: 24.0175, address: '合成路, Test District, Test City', latitude: null, longitude: null, charge_details: [] }
  Reflect.deleteProperty(legacy, 'currency_code')
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
  const chart = tree!.root.findByProps({ className: 'card charge-month-chart' })
  const cells = chart.findAllByType('button').filter(node => node.props.className?.includes('charge-activity-cell'))
  expect(cells.filter(node => node.props.ariaLabel.includes('已知 1 / 1 条'))).toHaveLength(1)
  expect(cells.find(node => node.props.ariaLabel.includes('已知 1 / 1 条'))!.props.ariaLabel).toContain('24.02 kWh')
  await act(async () => cells.find(node => node.props.ariaLabel.includes('已知 1 / 1 条'))!.props.onClick())
  expect(chart.findByProps({ className: 'charge-activity-selection' }).children.join('')).toContain('24.02 kWh')
  expect(chart.findAllByProps({ className: 'muted' }).some(node => node.children.join('').includes('已返回的 1 条记录'))).toBe(true)
  await detail()
  const panel = tree!.root.findByProps({ className: 'history-detail-overlay' })
  expect(panel.findByProps({ ariaLabel: '充电费用 ≈¥27.38' })).toBeDefined()
  expect(shown()).toContain('费用来源：按 App 默认 ¥1.14/kWh 与合格电池充入量估算')
  expect(panel.findAllByType('map' as React.ElementType)).toHaveLength(0)
  expect(panel.findByProps({ ariaLabel: '报告电压 230 V' })).toBeDefined()
  expect(panel.findByProps({ ariaLabel: '报告电流 0 A' })).toBeDefined()
})
it('does not estimate a charge when its currency is explicitly unknown', async () => {
  const unknownCurrency = { ...charge, source: 'teslamate_archive', cost: null, currency_code: null, energy_contract: null, charge_energy_added: 10 }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars')
    ? response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }) : url.endsWith('/current') ? response({ charge: null })
      : response({ charges: [unknownCurrency], meta: { page: 1, show: 20, total_pages: 1 } }))
  await mount()
  expect(tree!.root.findByProps({ className: 'history-record' }).findByProps({ ariaLabel: '充电费用 暂无数据' })).toBeDefined()
})
it('keeps missing energy, SOC, cost and power unknown in the overview', async () => {
  const unknown = { ...charge, cost: null, currency_code: null, charger_power: null, energy_contract: null,
    battery_details: { start_battery_level: null, end_battery_level: null }, charge_details: [] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars')
    ? response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] }) : url.endsWith('/current') ? response({ charge: null })
      : url.endsWith('/charges/1') ? response({ charge: unknown })
        : response({ charges: [unknown], meta: { page: 1, show: 20, total_pages: 1 } }))
  await mount(); await detail()
  const overview = tree!.root.findByProps({ className: 'card charge-detail-intro' })
  for (const label of ['电池充入 暂无数据', 'SOC 暂无数据', '充电费用 暂无数据', '报告功率 暂无数据']) {
    expect(overview.findByProps({ ariaLabel: label }), label).toBeDefined()
  }
  expect(tree!.root.findByProps({ ariaLabel: '充电详情' }).findAllByType('map' as React.ElementType)).toHaveLength(0)
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
  const panel = tree!.root.findByProps({ className: 'history-detail-surface' })
  const sections = panel.findAll(node => node.props.className === 'card charge-detail-map-card' || node.props.className === 'card charge-detail-intro')
  expect(sections.map(node => node.props.className)).toEqual(['card charge-detail-map-card', 'card charge-detail-intro'])
  const overview = sections[1]
  for (const label of ['电池充入 8 kWh', 'SOC 20→80%', '充电费用 0 EUR', '报告功率 0 kW']) {
    expect(overview.findByProps({ ariaLabel: label }), label).toBeDefined()
  }
  expect(JSON.stringify([...platform.values.values()])).not.toContain('31.2')
})
it('keeps a missing CNY bill estimate separate from an actual foreign-currency zero in the summary', async () => {
  const legacy = { ...charge, charge_id: 1, session_id: 'legacy-1', source: 'teslamate_archive', cost: null, currency_code: null,
    energy_contract: null, charge_energy_added: 24.0175, charge_details: [] }
  Reflect.deleteProperty(legacy, 'currency_code')
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
  { currency: 'CNY', defaultPrice: 0, card: '充电费用 ≈¥0.00', chart: '≈¥0.00', source: '已保存默认电价 ¥0/kWh' },
  { currency: 'EUR', defaultPrice: 2, card: '充电费用 ≈20.00 EUR', chart: '≈20.00 EUR', source: '已保存默认电价 2 EUR/kWh' },
])('uses scoped saved $currency default price $defaultPrice before the App fallback, without using TOU rates', async ({ currency, defaultPrice, card, source }) => {
  const { tariffKey } = await import('../../domain/tariff')
  platform.values.set(tariffKey('owner', origin), { currency, defaultPrice, enabled: true, peak: 9, flat: 8, valley: 7, timeZone: 'Asia/Shanghai' })
  const legacy = { ...charge, source: 'teslamate_archive', cost: null, currency_code: null, energy_contract: null, charge_energy_added: 10, charge_details: [] }
  Reflect.deleteProperty(legacy, 'currency_code')
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
  const cell = tree!.root.findAllByType('button').find(node => node.props.className?.includes('charge-activity-cell') && node.props.ariaLabel.includes('已知 1 / 1 条'))!
  expect(cell.props.ariaLabel).toContain('10.00 kWh')
  expect(tree!.root.findAllByProps({ className: 'charge-chart-tabs' })).toHaveLength(1)
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
  Reflect.deleteProperty(legacy, 'currency_code')
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
  expect(tree!.root.findAllByType('button').some(node => node.children.join('') === '编辑本机费用')).toBe(false)
})

it.each([false, true])('labels cross-channel inference and excludes source conflicts (conflict=%s)', async conflict => {
  const partial = { ...charge, charge_details: [
    { date: start, battery_level: 20 },
    { date: '2026-10-14T00:10:00Z', charger_details: { charger_power: 0 } },
    { date: end, battery_level: 80 },
    ...(conflict ? [{ date: '2026-10-14T00:20:00Z', source: 'foreign-source', charger_details: { charger_power: 999 } }] : []),
  ] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: partial })
    return response({ charges: [partial], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount(); await detail()
  const panel = tree!.root.findByProps({ className: 'history-detail-overlay' })
  const curves = panel.findAllByProps({ className: 'card analysis-curve' })
  const soc = curves.find(curve => curve.findAllByProps({ className: 'section-title' }).some(title => title.children.join('') === '充电电量变化'))!
  const socText = soc.findAllByType('text').map(node => node.children.join('')).join(' ')
  if (conflict) expect(socText).not.toContain('虚线连接观测点')
  else expect(socText).toContain('虚线连接观测点')
  expect(panel.findByProps({ ariaLabel: '样本最高功率 0 kW' })).toBeDefined()
  expect(panel.findAllByType('text').map(node => node.children.join('')).join(' ')).not.toContain('999')
})

it.each([
  ['without average power', null],
  ['with low average power', 8],
  ['with high average power', 20],
] as const)('does not infer a charging mode %s', async (_name, energy) => {
  const row = { ...charge, source: 'teslamate_archive', charge_type: null, energy_contract: null, charge_energy_added: energy, cost: 4 }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: row })
    return response({ charges: [row], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  const record = tree!.root.findByProps({ className: 'history-record' })
  expect(record.findByProps({ className: 'history-charge-mode history-charge-mode-unknown' }).children.join('')).toBe('模式未确认')
  expect(record.findAllByProps({ className: 'history-charge-submetrics' }).some(node => node.children.join('').includes('均功 ≈'))).toBe(energy !== null)
  await detail()
  expect(shown()).toContain('模式未确认')
})

it('keeps displayed charging modes consistent with AC and DC filters', async () => {
  const legacy = { ...charge, source: 'teslamate_archive', energy_contract: null, cost: 4, charge_energy_added: 8 }
  const rows = [
    { ...legacy, charge_id: 1, session_id: 'synthetic-ac', charge_type: 'ac' },
    { ...legacy, charge_id: 2, session_id: 'synthetic-dc', charge_type: 'dc' },
    { ...legacy, charge_id: 3, session_id: 'synthetic-unknown-high', charge_type: null, charge_energy_added: 20 },
    { ...legacy, charge_id: 4, session_id: 'synthetic-unknown-empty', charge_type: null, charge_energy_added: null },
  ]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    return response({ charges: rows, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  const records = () => tree!.root.findAllByProps({ className: 'history-record' })
  const displayedAC = records().filter(row => row.findAllByProps({ className: 'history-charge-mode history-charge-mode-ac' }).length).length
  const displayedDC = records().filter(row => row.findAllByProps({ className: 'history-charge-mode history-charge-mode-dc' }).length).length
  expect(displayedAC).toBe(1)
  expect(displayedDC).toBe(1)
  expect(records().filter(row => row.findAllByProps({ className: 'history-charge-mode history-charge-mode-unknown' }).length)).toHaveLength(2)
  const selector = () => tree!.root.findAllByType('picker' as React.ElementType).find(node => node.props.range?.join('|') === '全部|AC|DC')!
  await act(async () => selector().props.onChange({ detail: { value: 1 } })); await flush()
  expect(records()).toHaveLength(displayedAC)
  await act(async () => selector().props.onChange({ detail: { value: 2 } })); await flush()
  expect(records()).toHaveLength(displayedDC)
})

it.each([
  ['reported AC', { ...charge, source: 'teslamate_archive', energy_contract: null, charge_type: 'ac', charge_energy_added: 8 }, 'ac', 'AC 充电'],
  ['reported DC', { ...charge, source: 'teslamate_archive', energy_contract: null, charge_type: 'dc', charge_energy_added: 8 }, 'dc', 'DC 充电'],
  ['reported fast charger', { ...charge, source: 'teslamate_archive', energy_contract: null, charge_type: null, fast_charger_present: true, charge_energy_added: 8 }, 'dc', 'DC 充电'],
  ['verified AC contract', charge, 'ac', 'AC 充电'],
  ['verified DC contract', { ...charge, charge_type: 'dc', energy_contract: { ...charge.energy_contract, charge_mode: 'dc', ac_input: null } }, 'dc', 'DC 充电'],
  ['invalid contract and weak AC report', { ...charge, energy_contract: { ...charge.energy_contract, charge_mode_evidence: 'unverified' } }, 'unknown', '模式未确认'],
] as const)('uses explicit evidence for %s', async (_name, row, mode, label) => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: row })
    return response({ charges: [row], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  expect(tree!.root.findByProps({ className: `history-charge-mode history-charge-mode-${mode}` }).children.join('')).toBe(label)
  await detail()
  expect(shown()).toContain(label)
})

it('does not revive a failed mode contract from a weak cached AC label', async () => {
  await mount(); await act(async () => { platform.hide() })
  const cached = platform.values.get(historyCacheKey('charges', 'owner', 'uid7', origin)) as { items: Array<{ chargeMode: string; energy: { chargeMode: string; evidence: { charge_mode_evidence: string } } }> }
  expect(cached.items).toHaveLength(1)
  cached.items[0].chargeMode = 'ac'
  cached.items[0].energy.chargeMode = 'ac'
  cached.items[0].energy.evidence.charge_mode_evidence = 'unverified'
  offline = true
  await act(async () => { platform.show() }); await flush()
  expect(tree!.root.findByProps({ className: 'history-charge-mode history-charge-mode-unknown' }).children.join('')).toBe('模式未确认')
  await detail()
  expect(shown()).toContain('模式未确认')
})

it.each([
  ['missing', undefined, undefined, 8, '币种未知'],
  ['explicit null', null, undefined, 8, '币种未知'],
  ['invalid', 'INVALID', undefined, 8, '币种未知'],
  ['conflicting', 'USD', 'EUR', 8, '币种未知'],
  ['actual zero without currency', undefined, undefined, 0, '币种未知'],
  ['CNY', 'CNY', undefined, 8, '¥'],
  ['foreign currency', 'EUR', undefined, 8, 'EUR'],
] as const)('shows unit cost with %s currency without inventing a currency', async (_name, code, alternate, cost, expected) => {
  const row: Record<string, unknown> = { ...charge, cost, currency_code: code, currency: alternate }
  if (code === undefined) delete row.currency_code
  if (alternate === undefined) delete row.currency
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/charges/1')) return response({ charge: row })
    return response({ charges: [row], meta: { page: 1, show: 20, total_pages: 1 } })
  })
  await mount()
  const record = tree!.root.findByProps({ className: 'history-record' })
  const price = record.findByProps({ className: 'history-charge-submetrics' }).children.join('')
  expect(price).toContain(expected)
  expect(price).toContain(cost === 0 ? '0.00/kWh' : '1.00/kWh')
  if (expected === '币种未知') expect(price).not.toContain('¥')
  expect(record.findByProps({ ariaLabel: `充电费用 ${cost} ${expected === '币种未知' ? expected : code}` })).toBeDefined()
})
