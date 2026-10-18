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
  navigateTo: vi.fn(), switchTab: vi.fn(), showModal: vi.fn(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Image: 'image', Map: 'map', ScrollView: 'scroll-view' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => { if (platform.quota) throw Error('fixture quota'); platform.values.set(key, value) },
  removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
  navigateTo: platform.navigateTo, switchTab: platform.switchTab, showModal: platform.showModal,
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn },
useUnload: (fn: () => void) => { platform.unload = fn } }))

let renderer: ReactTestRenderer | null = null
const cars = [{ car_id: 7, vehicle_uid: 'uid7', name: 'Car A' }, { car_id: 8, vehicle_uid: 'uid8', name: 'Car B' }]
const respond = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
const session = (userId: string) => ({ apiOrigin: process.env.TARO_APP_API_BASE_URL, accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
const report = (power: number) => ({ status: { state: 'online', battery_details: { battery_level: 0, rated_battery_range: 0 },
  odometer: 0, car_geodata: { latitude: 0, longitude: 0 }, car_status: { locked: false },
  climate_details: { is_climate_on: false }, driving_details: { speed: 0, power }, charging_details: { charger_power: 0 } } })
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.quota = false; platform.request.mockReset()
  platform.navigateTo.mockReset(); platform.switchTab.mockReset(); platform.showModal.mockReset()
  platform.values.set('matelink.wechat.session.v1', session('a'))
})
afterEach(async () => {
  if (renderer) { await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null }
  vi.useRealTimers()
  vi.unstubAllEnvs()
})
async function flush() { await act(async () => { if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0); else await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount(openDiagnostics = true) {
  const Page = (await import('./index')).default
  await act(async () => { renderer = TestRenderer.create(React.createElement(Page)) })
  await act(async () => { platform.show() }); await flush()
  if (openDiagnostics) {
    const button = renderer!.root.findAllByType('button').find(node => node.children.join('') === '查看数据状态')
    if (button) await act(async () => { button.props.onClick() })
  }
  return renderer!
}
const visible = () => JSON.stringify(renderer!.toJSON())
const snapshotBadge = () => renderer!.root.findAll(node => node.type === 'text' && String(node.props.className ?? '').split(' ').includes('dashboard-snapshot-badge'))[0]
const snapshots = () => platform.request.mock.calls.filter(([request]) => request.url.endsWith('/snapshot'))
const advance = async (milliseconds: number) => { await act(async () => { await vi.advanceTimersByTimeAsync(milliseconds) }) }
it.each([{ state: 'driving', speed: 0, delay: 8000 }, { state: 'online', speed: 1, delay: 8000 },
  { state: 'charging', speed: 0, delay: 15000 }, { state: 'asleep', speed: 0, delay: 60000 },
  { state: 'online', speed: 0, delay: 10000 }])('polls only snapshots at the App $state/$speed cadence without resetting the image', async ({ state, speed, delay }) => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/snapshot') ? respond({ status: { state, driving_details: { speed } } }) : online(url))
  const assets = await import('../../services/vehicle_assets'), image = vi.spyOn(assets, 'ensureVehicleAsset').mockImplementation(async plan => plan.assetPath!)
  try {
    vi.useFakeTimers(); await mount(false)
    const before = platform.request.mock.calls.length, imageCalls = image.mock.calls.length
    await advance(delay - 1); expect(snapshots()).toHaveLength(1)
    await advance(1); expect(snapshots()).toHaveLength(2)
    expect(platform.request.mock.calls.slice(before).map(([request]) => request.url)).toEqual([snapshots()[1][0].url])
    expect(image).toHaveBeenCalledTimes(imageCalls)
    await act(async () => { platform.hide() }); await advance(delay * 2)
    expect(snapshots()).toHaveLength(2)
    await act(async () => { platform.show() }); await advance(0)
    expect(snapshots()).toHaveLength(3)
    await advance(delay); expect(snapshots()).toHaveLength(4)
  } finally { image.mockRestore() }
})
it('does not overlap a hung snapshot and expires the live badge using the independent foreground clock', async () => {
  const stamp = new Date().toISOString()
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/snapshot') ? respond({ source: 'live_mqtt', observed_at: stamp, status: { state: 'online' } }) : online(url))
  vi.useFakeTimers(); await mount(false); expect(snapshotBadge().props['data-kind']).toBe('live')
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/snapshot') ? new Promise(() => {}) : Promise.resolve(online(url)))
  await advance(121000)
  expect(snapshots()).toHaveLength(2)
  expect(snapshotBadge().props['data-kind']).toBe('history')
})
it.each(['hide', 'unload', 'car', 'account'])('rejects a late polled snapshot after %s invalidates its scope', async change => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  vi.useFakeTimers(); await mount(false)
  let finish: (value: unknown) => void = () => {}
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/snapshot') ? new Promise(resolve => { finish = resolve }) : Promise.resolve(online(url)))
  await advance(10000); const oldFinish = finish
  if (change === 'hide') await act(async () => { platform.hide() })
  if (change === 'unload') await act(async () => { platform.unload() })
  if (change === 'car') {
    platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
    await act(async () => { renderer!.root.findByType('picker' as React.ElementType).props.onChange({ detail: { value: 1 } }) })
    await advance(0)
  }
  if (change === 'account') { platform.values.set('matelink.wechat.session.v1', session('b')); await advance(1000) }
  await act(async () => { oldFinish(respond(report(98765))) })
  expect(visible()).not.toContain('98765')
  if (change === 'car') expect(visible()).toContain('Car B')
  if (change === 'account') expect(visible()).not.toContain('Car A')
})
it('uses the newly received driving state for the following snapshot interval', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  vi.useFakeTimers(); await mount(false)
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/snapshot') ? respond({ status: { state: 'driving' } }) : online(url))
  await advance(10000); expect(snapshots()).toHaveLength(2)
  await advance(7999); expect(snapshots()).toHaveLength(2)
  await advance(1); expect(snapshots()).toHaveLength(3)
})
async function diagnosticEvidence() {
  const Checklist = (await import('../../components/ReadinessChecklist')).default
  const props = renderer!.root.findByType(Checklist).props
  return diagnosticSteps({ ...props.diagnostics, items: props.items, pairing: props.pairing, sessionReady: props.sessionReady })
}
async function choose(index: number) {
  await act(async () => { renderer!.root.findByType('picker' as React.ElementType).props.onChange({ detail: { value: index } }) })
  await flush()
}
async function editAppearance() {
  await act(async () => { renderer!.root.findByProps({ ariaLabel: '选择车辆外观' }).props.onClick({ stopPropagation() {} }) })
  return renderer!.root.findByProps({ className: 'appearance-dialog' })
}
it('keeps the account model in the manual editor when colour information is missing', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? respond({ cars: [{ ...cars[0], model: 'S' }] }) : online(url))
  await mount(false)
  const dialog = await editAppearance()
  const modelPicker = dialog.findAllByType('picker' as React.ElementType)[0]
  expect(modelPicker.props.range[modelPicker.props.value]).toBe('Model S')
  await act(async () => { dialog.findAllByType('button').find(node => node.children.join('') === '保存外观')!.props.onClick() })
  const { readVehicleAppearance } = await import('../../services/vehicle_appearance')
  expect(readVehicleAppearance({ getStorageSync: key => platform.values.get(key) }, { origin: 'https://api.example.test', userId: 'a', stableId: 'uid7' })).toMatchObject({ model: 'S', variant: 'ms' })
})
it.each(['y', 'Model Y'])('opens and saves the appearance editor for a genuine API model alias %s', async model => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? respond({ cars: [{ ...cars[0], model, car_exterior: { exterior_color: 'PX02', wheel_type: 'WY19P' } }] }) : online(url))
  await mount(false)
  const dialog = await editAppearance()
  expect(dialog.findAllByType('picker' as React.ElementType)[0].props.range).toContain('Model Y')
  await act(async () => { dialog.findAllByType('button').find(node => node.children.join('') === '保存外观')!.props.onClick() })
  const { readVehicleAppearance } = await import('../../services/vehicle_appearance')
  expect(readVehicleAppearance({ getStorageSync: key => platform.values.get(key) }, { origin: 'https://api.example.test', userId: 'a', stableId: 'uid7' })).toMatchObject({ model: 'Y', variant: 'myj', exteriorColor: 'PX02' })
})
it('loads the default preview after page-show invalidation and reloads it when the same page is shown again', async () => {
  platform.values.delete('matelink.wechat.session.v1')
  const assets = await import('../../services/vehicle_assets')
  const loading = vi.spyOn(assets, 'ensureVehicleAsset').mockImplementation(async plan => plan.assetPath!)
  try {
    await mount(false); await flush()
    expect(loading.mock.calls.some(([plan]) => plan.assetKey === 'myj_PX02_WY19P')).toBe(true)
    expect(renderer!.root.findAllByProps({ className: 'dashboard-vehicle-placeholder dashboard-art-placeholder' })).toHaveLength(0)
    const before = loading.mock.calls.length
    await act(async () => { platform.hide(); platform.show() }); await flush()
    expect(loading.mock.calls.length).toBeGreaterThan(before)
    expect(renderer!.root.findAllByProps({ className: 'dashboard-vehicle-placeholder dashboard-art-placeholder' })).toHaveLength(0)
  } finally { loading.mockRestore() }
})
it('starts with an explicitly labelled black refreshed Y preview and never adopts its settings as an authenticated vehicle', async () => {
  platform.values.delete('matelink.wechat.session.v1')
  await mount(false)
  expect(visible()).toContain('默认外观 · 黑色 Model Y 焕新版')
  expect(visible()).toContain('尚未绑定车辆')
  expect(platform.request).not.toHaveBeenCalled()
  const dialog = await editAppearance()
  await act(async () => { dialog.findAllByType('picker' as React.ElementType)[0].props.onChange({ detail: { value: 1 } }) })
  await act(async () => { dialog.findAllByType('picker' as React.ElementType)[3].props.onChange({ detail: { value: 4 } }) })
  await act(async () => { dialog.findAllByType('button').find(node => node.children.join('') === '保存外观')!.props.onClick() })
  const { readVehicleAppearance } = await import('../../services/vehicle_appearance')
  const origin = 'https://api.example.test', storage = { getStorageSync: (key: string) => platform.values.get(key) }
  expect(readVehicleAppearance(storage, { origin, userId: null, stableId: null })).toMatchObject({ model: '3', variant: 'm3h', exteriorColor: 'PPSB' })
  platform.values.set('matelink.wechat.session.v1', session('a'))
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? respond({ cars: [{ ...cars[0], model: 'Y', car_exterior: { exterior_color: 'PMNG', wheel_type: 'Gemini19' } }] }) : online(url))
  await act(async () => { platform.show() }); await flush()
  expect(readVehicleAppearance(storage, { origin, userId: 'a', stableId: 'uid7' })).toBeNull()
  expect(visible()).not.toContain('手动外观')
  expect(renderer!.root.findByType('vehicle-asset' as React.ElementType).props['aria-label']).toContain('Model Y')
})

it('persists manual year and colour for the selected vehicle, isolates switches, and supports restoring automatic matching', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  await mount(false)
  let dialog = await editAppearance()
  const yearPicker = dialog.findAllByType('picker' as React.ElementType)[2]
  await act(async () => { yearPicker.props.onChange({ detail: { value: yearPicker.props.range.indexOf('2025款') } }) })
  await act(async () => { dialog.findAllByType('picker' as React.ElementType)[3].props.onChange({ detail: { value: 4 } }) })
  await act(async () => { dialog.findAllByType('button').find(node => node.children.join('') === '保存外观')!.props.onClick() })
  expect(visible()).toContain('手动外观 · 2025款')
  const { readVehicleAppearance } = await import('../../services/vehicle_appearance')
  const scope = { origin: 'https://api.example.test', userId: 'a', stableId: 'uid7' }, storage = { getStorageSync: (key: string) => platform.values.get(key) }
  expect(readVehicleAppearance(storage, scope)).toMatchObject({ model: 'Y', variant: 'myj', modelYear: 2025, exteriorColor: 'PR01' })
  await choose(1); expect(visible()).not.toContain('手动外观')
  await choose(0); expect(visible()).toContain('手动外观 · 2025款')
  dialog = await editAppearance()
  await act(async () => { dialog.findAllByType('button').find(node => node.children.join('') === '恢复自动识别')!.props.onClick() })
  expect(readVehicleAppearance(storage, scope)).toBeNull()
  expect(visible()).not.toContain('手动外观')
})

it('keeps appearance changes unsaved when storage fails or the owning session changes while the editor is open', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => online(url))
  await mount(false)
  const dialog = await editAppearance(), save = dialog.findAllByType('button').find(node => node.children.join('') === '保存外观')!
  platform.quota = true
  await act(async () => { save.props.onClick() })
  expect(visible()).toContain('外观设置未能保存')
  platform.quota = false; platform.values.set('matelink.wechat.session.v1', session('b'))
  await act(async () => { save.props.onClick() })
  expect([...platform.values.keys()].some(key => key.startsWith('matelink.vehicle-appearance.v1.'))).toBe(false)
})
function online(url: string) {
  if (url.endsWith('/cars')) return respond({ cars })
  if (url.endsWith('/snapshot')) return respond(report(url.includes('/cars/8/') ? -22 : -11))
  if (url.endsWith('/data-readiness')) return respond({ items: [] })
  return respond({ status: 'available' })
}

it('matches the actual Dashboard hierarchy, App InfoCard ordering, four state chips, target line and real map placement', async () => {
  const stamp = new Date().toISOString()
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return respond({ cars })
    if (url.endsWith('/snapshot')) return respond({ source: 'live_mqtt', observed_at: stamp, field_sources: { Soc: 'live_mqtt' }, status: {
      state: 'online', battery_details: { battery_level: 80, rated_battery_range: 0, ideal_battery_range: 300 },
      charging_details: { charge_limit_soc: 90, plugged_in: true }, car_status: { locked: false, sentry_mode: false, doors_open: true },
      climate_details: { is_climate_on: false, inside_temp: 20, outside_temp: -5 }, odometer: 1234,
      car_geodata: { latitude: 31.2, longitude: 121.5, geofence: '合成地点' },
    } })
    if (url.endsWith('/data-readiness')) return respond({ items: [] })
    return respond({})
  })
  const page = await mount(false)
  const hero = page.root.findByProps({ className: 'dashboard-hero' })
  expect(hero.findByProps({ className: 'dashboard-soc-value' }).children.join('')).toBe('80%')
  expect(hero.findByProps({ className: 'dashboard-soc-label' }).children.join('')).toBe('电量')
  expect(hero.findByProps({ ariaLabel: '续航 0 km' })).toBeDefined()
  expect(hero.findAllByType('text').some(node => node.children.join('') === '当前 80% · 充电目标 90%')).toBe(true)
  const chips = hero.findAllByProps({ className: 'dashboard-state-chip' })
  expect(chips.map(node => node.findByProps({ className: 'dashboard-chip-label' }).children.join(''))).toEqual(['未锁', '已插枪', '空调关闭', '哨兵关闭'])
  expect(hero.findByProps({ className: 'dashboard-opening-alert' })).toBeDefined()
  const info = page.root.findAllByProps({ className: 'dashboard-info-card' })
  expect(info.slice(0, 4).map(node => node.findByProps({ className: 'dashboard-info-label' }).children.join(''))).toEqual(['里程', '位置', '车内温度', '车外温度'])
  for (const card of info) expect(card.children.map(child => typeof child === 'string' ? '' : child.props.className).slice(0, 2)).toEqual(['dashboard-info-heading', 'dashboard-info-value'])
  const map = page.root.findByType('map' as React.ElementType)
  expect(map.props.showLocation).toBe(false)
  expect(map.props.latitude).not.toBe(31.2)
  const sections = page.root.findByProps({ className: 'page vehicle-page' }).findAllByType('view').map(node => node.props.className)
  expect(sections.indexOf('dashboard-location-row')).toBeLessThan(sections.indexOf('dashboard-map-card'))
  expect(sections.indexOf('dashboard-map-card')).toBeLessThan(sections.indexOf('dashboard-temperature-row'))
  expect(snapshotBadge().children.join('')).toContain('实时')
})

it('uses the full charging HUD and lower three-column card without replacing missing current/energy with zero', async () => {
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return respond({ cars })
    if (url.endsWith('/snapshot')) return respond({ source: 'fleet_api', observed_at: null, status: { state: 'charging',
      battery_details: { battery_level: 0, rated_battery_range: null, ideal_battery_range: 0, est_battery_range: 99 },
      charging_details: { charging_state: 'Charging', charger_power: 0, charger_voltage: 230, charger_actual_current: null, charge_energy_added: null, time_to_full_charge: 1.5, charge_limit_soc: 0 } } })
    if (url.endsWith('/data-readiness')) return respond({ items: [] })
    return respond({})
  })
  const page = await mount(false)
  const hud = page.root.findByProps({ className: 'dashboard-hud dashboard-hud-charging' })
  expect(hud.findAllByType('text').map(node => node.children.join('')).join(' ')).not.toContain('0 kWh')
  expect(hud.findByProps({ ariaLabel: '充电电压电流 230 V / 暂无数据 A' })).toBeDefined()
  const lower = page.root.findByProps({ className: 'dashboard-current-charge' })
  expect(lower.findByProps({ ariaLabel: '剩余时间 1小时30分钟' })).toBeDefined()
  expect(lower.findByProps({ ariaLabel: '已充入 暂无数据 kWh' })).toBeDefined()
  expect(snapshotBadge().children.join('')).not.toContain('实时')
  const dismiss = hud.findAllByType('button').find(node => node.children.join('') === '关闭浮窗')!
  await act(async () => { dismiss.props.onClick({ stopPropagation() {} }) })
  expect(page.root.findAllByProps({ className: 'dashboard-hud dashboard-hud-charging' })).toHaveLength(0)
  expect(page.root.findByProps({ className: 'dashboard-hud-collapsed' })).toBeDefined()
})

it.each(fixture.readiness_cases)('shared synthetic $id runs through the API normalizer and actual diagnostic component', async row => {
  vi.stubEnv('TARO_APP_API_BASE_URL', row.scope.origin)
  platform.values.set('matelink.wechat.session.v1', session(row.scope.account))
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return respond({ cars: [{ car_id: 7, vehicle_uid: row.scope.vehicle_uid }] })
    if (url.endsWith('/snapshot')) return respond({ status: row.rawStatus ?? {}, observed_at: row.rawStatus?.observed_at, source: row.rawStatus?.source })
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
  expect(visible()).toContain('胎压为有界本机前台观测历史')
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
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/cars/7/snapshot')
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
  platform.request.mockImplementation(({ url }: { url: string }) => url.endsWith('/cars/7/snapshot')
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
  expect(visible()).toContain('0.0000, 0.0000')
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
    if (defer && url.endsWith('/cars/7/snapshot')) return new Promise(resolve => { release = resolve })
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
    if (url.endsWith('/cars/7/snapshot')) return new Promise(resolve => { release = resolve })
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
  expect(platform.request.mock.calls.some(([value]) => value.url.endsWith('/cars/8/snapshot'))).toBe(true)
})

it('driving values follow App speed, recovery and gear order after actual unit parsing; no guessed gear', async () => {
 platform.request.mockImplementation(async ({ url }: { url: string }) => {
  if (url.endsWith('/cars')) return respond({ cars: [{ ...cars[0], model: '3', car_details: { trim_badging: '74D' } }] })
  if (url.endsWith('/snapshot')) return respond({ source: 'fleet_api', observed_at: 'bad', units: { unit_of_length: 'mi', unit_of_temperature: 'F' }, status: {
   state: 'driving', battery_details: { battery_level: 0, rated_battery_range: null, ideal_battery_range: 10 },
   driving_details: { speed: 10, power: -11, shift_state: ' d ' }, climate_details: { inside_temp: 32, outside_temp: -4 },
  } })
  return url.endsWith('/data-readiness') ? respond({ items: [] }) : respond({})
 })
 const page = await mount(false)
 const strip = page.root.findByProps({ className: 'dashboard-driving-strip' })
 expect(strip.children.map(node => typeof node === 'string' ? node : node.props.ariaLabel)).toEqual(['速度 16 km/h', '回收 11 kW，原始功率 -11 kW', '挡位 D'])
 expect(page.root.findByProps({ ariaLabel: '续航 16 km' })).toBeDefined()
 expect(page.root.findByProps({ ariaLabel: '车内温度 0 °C' })).toBeDefined()
 expect(page.root.findByProps({ ariaLabel: '车外温度 -20 °C' })).toBeDefined()
 expect(visible()).toContain('Model 3 · 长续航版')
 expect(snapshotBadge().children.join('')).toBe('时间未知')
 const Hero = (await import('../../components/dashboard/DashboardParts')).DashboardHero
 expect(page.root.findByType(Hero).props.status.power).toBe(-11)
 expect(page.root.findByType(Hero).props.status.batteryLevel).toBe(0)
 expect(page.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
 await act(async () => { page.root.findByProps({ ariaLabel: '位置 暂无数据' }).props.onClick() })
 expect(page.root.findAllByProps({ className: 'diagnostic-steps' }).length || visible().includes('数据接收')).toBeTruthy()
 expect(platform.navigateTo).not.toHaveBeenCalled()
})

it('fresh source metadata cannot become LIVE without time and cached reopen never restores private location', async () => {
 const stamp = new Date().toISOString()
 platform.request.mockImplementation(async ({ url }: { url: string }) => {
  if (url.endsWith('/cars')) return respond({ cars: [{ ...cars[0], model: 'y' }] })
  if (url.endsWith('/snapshot')) return respond({ source: 'live_mqtt', observed_at: stamp, status: { state: 'online', battery_details: { battery_level: 0, rated_battery_range: 0 }, car_geodata: { latitude: 31.2, longitude: 121.5, geofence: 'Private live position' } } })
  return url.endsWith('/data-readiness') ? respond({ items: [] }) : respond({})
 })
 await mount(false)
 expect(snapshotBadge().children.join('')).toBe('实时数据')
 await act(async () => { platform.hide(); renderer!.unmount() }); renderer = null
 platform.request.mockReset().mockRejectedValue(Error('offline'))
 const page = await mount(false)
 expect(snapshotBadge().children.join('')).toBe('缓存快照')
 expect(page.root.findByProps({ className: 'dashboard-soc-value' }).children.join('')).toBe('0%')
 expect(page.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
 expect(visible()).not.toContain('Private live position')
 expect(platform.request).toHaveBeenCalledTimes(1)
 expect(page.root.findByType('vehicle-asset' as React.ElementType)).toBeDefined()
})

it('status failure uses the App partial dashboard and real car configuration, without fake totals, while appearance selection stays usable', async () => {
 platform.request.mockImplementation(async ({ url }: { url: string }) => {
  if (url.endsWith('/cars')) return respond({ cars: [{ ...cars[0], model: 'Y', car_details: { trim_badging: 'P' }, car_exterior: { exterior_color: 'White', wheel_type: 'Gemini19' } }] })
  if (url.endsWith('/snapshot')) throw Error('offline')
  return url.endsWith('/data-readiness') ? respond({ items: [] }) : respond({})
 })
 const page = await mount(false)
 expect(page.root.findByProps({ className: 'dashboard-partial' })).toBeDefined()
 expect(page.root.findByType('vehicle-asset' as React.ElementType)).toBeDefined()
 expect(page.root.findByProps({ ariaLabel: '总行程 暂无数据' })).toBeDefined()
 expect(page.root.findByProps({ ariaLabel: '总充电 暂无数据' })).toBeDefined()
 expect(visible()).toContain('Model Y · 性能版')
 const camera = page.root.findByProps({ ariaLabel: '选择车辆外观' })
 expect(camera.props.disabled).toBe(false)
 await act(async () => { camera.props.onClick({ stopPropagation() {} }) })
 expect(page.root.findByProps({ className: 'appearance-dialog' })).toBeDefined()
 expect(visible()).not.toContain('待接入')
})

it('map render failure preserves location evidence and offers an actual retry without phone GPS', async () => {
 platform.request.mockImplementation(async ({ url }: { url: string }) => {
  if (url.endsWith('/cars')) return respond({ cars })
  if (url.endsWith('/snapshot')) return respond({ status: { car_geodata: { latitude: 31.2, longitude: 121.5 } } })
  return url.endsWith('/data-readiness') ? respond({ items: [] }) : respond({})
 })
 const page = await mount(false)
 await act(async () => { page.root.findByType('map' as React.ElementType).props.onError() })
 expect(page.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
 expect(visible()).toContain('31.2000, 121.5000')
 await act(async () => { page.root.findAllByType('button').find(node => node.children.join('') === '重试地图')!.props.onClick() })
 expect(page.root.findByType('map' as React.ElementType).props.showLocation).toBe(false)
})

it('does not invent P or put a driving strip on an online status from speed alone', async () => {
 platform.request.mockImplementation(async ({url}: {url:string}) => {
  if(url.endsWith('/cars')) return respond({cars})
  if(url.endsWith('/snapshot')) return respond({status:{ state:'online',driving_details:{speed:10,power:0,shift_state:'unknown'} }})
  return url.endsWith('/data-readiness') ? respond({items:[]}) : respond({})
 })
 const page=await mount(false)
 expect(page.root.findByProps({className:'dashboard-hud dashboard-hud-driving'})).toBeDefined()
 expect(page.root.findAllByProps({className:'dashboard-driving-strip'})).toHaveLength(0)
 expect(page.root.findAllByProps({className:'dashboard-hud-gear'})).toHaveLength(0)
})
