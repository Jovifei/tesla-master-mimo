import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({
  request: vi.fn(), values: new Map<string, unknown>(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void,
}))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Image: 'image', Map: 'map', ScrollView: 'scroll-view', Canvas: 'canvas', Slider: 'slider' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => platform.values.set(key, value),
  removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
  hideTabBar: vi.fn(), showTabBar: vi.fn(), navigateTo: vi.fn(),
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn },
useUnload: (fn: () => void) => { platform.unload = fn }, usePageScroll: () => {} }))

let page: ReactTestRenderer | null = null
const origin = 'https://api.example.test'
const car = { car_id: 7, vehicle_uid: 'drive-audit' }
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
const localDay = (offset: number) => { const now = new Date(); return new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset, 12) }
const dayKey = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
const drive = (id: number, offset: number, minutes = 0) => {
  const start = new Date(localDay(offset).getTime() + minutes * 60_000)
  return { drive_id: id, session_id: `drive-${id}`, source: 'teslamate_archive', source_instance_id: 'archive-A',
    source_vehicle_id: 'car-A', source_record_id: `record-${id}`, quality_state: 'observed',
    start_date: start.toISOString(), end_date: new Date(start.getTime() + 10 * 60_000).toISOString(), duration_min: 10,
    odometer_details: { odometer_distance: 2 }, battery_details: { start_battery_level: 80, end_battery_level: 79 },
    start_address: `合成起点-${id}`, end_address: `合成终点-${id}` }
}
const evidence = (item: ReturnType<typeof drive>) => ({ ...item, drive_details: [
  { date: item.start_date, latitude: 39.9, longitude: 116.4, speed: 10, power: 2 },
  { date: item.end_date, latitude: 39.91, longitude: 116.41, speed: 20, power: -2 },
] })
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount() {
  const { writeAppSession } = await import('../../services/session')
  writeAppSession({ setStorageSync: (key, value) => platform.values.set(key, value) },
    { apiOrigin: origin, accessToken: 'synthetic', refreshToken: null, expiresAt: null, userId: 'test-owner', linkRequired: false })
  const Page = (await import('./index')).default
  await act(async () => { page = TestRenderer.create(<Page />) })
  await act(async () => { platform.show() }); await flush(); await flush()
  return page!
}
async function clickText(view: ReactTestRenderer, text: string) {
  const button = view.root.findAllByType('button').find(node => node.children.join('') === text)
  expect(button).toBeDefined()
  await act(async () => { button!.props.onClick() }); await flush()
}
const front = (view: ReactTestRenderer) => view.root.findByProps({ className: 'day-stack-heading-row' }).findByProps({ className: 'section-title' }).children.join('')
beforeEach(() => { vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', origin); vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'false'); platform.values.clear(); platform.request.mockReset() })
afterEach(async () => { if (page) await act(async () => { platform.unload(); page!.unmount() }); page = null; vi.unstubAllEnvs() })

it.each(['evidence-first', 'detail-first'] as const)('does not mix a corrected detail window with stale samples, %s', async order => {
  const original = drive(1, 0), corrected = drive(1, 0, 30), pending: ((value: unknown) => void)[] = []
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (url.endsWith('/drives/1')) return new Promise(resolve => { pending.push(resolve) })
    return Promise.resolve(response({ drives: [original], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  expect(view.root.findAllByProps({ className: 'card fixture-data-notice' })).toHaveLength(0)
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
  expect(pending).toHaveLength(2)
  if (order === 'evidence-first') {
    await act(async () => { pending[0](response({ drive: evidence(original) })) }); await flush()
    expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(1)
    await act(async () => { pending[1](response({ drive: corrected })) }); await flush()
  } else {
    await act(async () => { pending[1](response({ drive: corrected })) }); await flush()
    await act(async () => { pending[0](response({ drive: evidence(original) })) }); await flush()
  }
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
  expect(view.root.findAllByProps({ id: 'drive-inline-speed' })).toHaveLength(0)
})

it.each(['evidence-first', 'detail-first'] as const)('shows same-window samples after a record correction, %s', async order => {
  const original = drive(1, 0), corrected = drive(1, 0, 30), pending: ((value: unknown) => void)[] = []
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (url.endsWith('/drives/1')) return new Promise(resolve => { pending.push(resolve) })
    return Promise.resolve(response({ drives: [original], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
  expect(pending).toHaveLength(2)
  if (order === 'evidence-first') {
    await act(async () => { pending[0](response({ drive: evidence(corrected) })) }); await flush()
    expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
    await act(async () => { pending[1](response({ drive: corrected })) }); await flush()
  } else {
    await act(async () => { pending[1](response({ drive: corrected })) }); await flush()
    await act(async () => { pending[0](response({ drive: evidence(corrected) })) }); await flush()
  }
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(1)
  expect(view.root.findAllByProps({ id: 'drive-inline-speed' }).length).toBeGreaterThan(0)
})

it('accepts equivalent timezone instants but rejects a nanosecond time-window correction', async () => {
  const original = drive(1, 0), pending: ((value: unknown) => void)[] = []
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (url.endsWith('/drives/1')) return new Promise(resolve => { pending.push(resolve) })
    return Promise.resolve(response({ drives: [original], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
  const offset = (value: string) => new Date(Date.parse(value) + 8 * 3_600_000).toISOString().replace('Z', '+08:00')
  await act(async () => { pending[0](response({ drive: { ...evidence(original), start_date: offset(original.start_date), end_date: offset(original.end_date) } })) }); await flush()
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(1)
  await act(async () => { pending[1](response({ drive: { ...original, end_date: original.end_date.replace('.000Z', '.000000001Z') } })) }); await flush()
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
})

it('ignores pending detail evidence after the page hides', async () => {
  const original = drive(1, 0), pending: ((value: unknown) => void)[] = []
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (url.endsWith('/drives/1')) return new Promise(resolve => { pending.push(resolve) })
    return Promise.resolve(response({ drives: [original], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
  await act(async () => { platform.hide() }); await flush()
  await act(async () => { pending[0](response({ drive: evidence(original) })); pending[1](response({ drive: original })) }); await flush()
  expect(view.root.findAllByProps({ className: 'history-detail-overlay' })).toHaveLength(0)
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
})

it('starts all-time history on today while keeping future, older and unknown records reachable', async () => {
  const future = drive(1, 1), old = drive(2, -1), unknown = { ...drive(3, 0), start_date: null, end_date: null }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: [future, old, unknown], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await clickText(view, '全部时间')
  expect(front(view)).toContain(dayKey(localDay(0)))
  expect(view.root.findAllByProps({ className: 'history-record' })).toHaveLength(0)
  expect(view.root.findByProps({ className: 'day-stack-body' }).props.onTouchEnd).toBeUndefined()
  await act(async () => { view.root.findByProps({ ariaLabel: '较新日期' }).props.onClick() }); await flush()
  expect(front(view)).toContain(dayKey(localDay(1)))
  await act(async () => { view.root.findByProps({ className: 'day-stack-close' }).props.onClick() }); await flush()
  await act(async () => { view.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick() }); await flush()
  expect(front(view)).toContain(dayKey(localDay(-1)))
  await clickText(view, '查看日期待确认')
  expect(front(view)).toBe('日期待确认')
})

it('retains a selected date through an empty-cache refresh and clears it when the filter changes', async () => {
  const old = drive(1, -1)
  let delayed = false
  let release: ((value: unknown) => void) | undefined
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (delayed && url.includes('/drives?')) return new Promise(resolve => { release = resolve })
    return Promise.resolve(response({ drives: [old], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  await clickText(view, '全部时间')
  const before = [...platform.values.entries()]
  await act(async () => { view.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick() }); await flush()
  expect(front(view)).toContain(dayKey(localDay(-1)))
  expect([...platform.values.entries()]).toEqual(before)
  const { historyCacheKey } = await import('../../services/history_cache')
  platform.values.delete(historyCacheKey('drives', 'test-owner', 'drive-audit', origin))
  delayed = true
  await act(async () => { platform.show() }); await flush()
  expect(release).toBeDefined()
  await act(async () => { release!(response({ drives: [old], meta: { page: 1, show: 20, total_pages: 1 } })) }); await flush()
  expect(front(view)).toContain(dayKey(localDay(-1)))
  await clickText(view, '通勤 (< 50km)')
  expect(front(view)).toContain(dayKey(localDay(0)))
  await clickText(view, '全部')
  expect(front(view)).toContain(dayKey(localDay(0)))
})

it('does not restore a selected date after switching vehicle and back', async () => {
  const other = { car_id: 8, vehicle_uid: 'drive-other' }, old = drive(1, -1)
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car, other] }) :
    response({ drives: [old], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await clickText(view, '全部时间')
  await act(async () => { view.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick() }); await flush()
  expect(front(view)).toContain(dayKey(localDay(-1)))
  const picker = () => view.root.findAllByType('picker' as React.ElementType).find(node => node.props.mode === 'selector' && node.props.range?.length === 2)!
  await act(async () => { picker().props.onChange({ detail: { value: 1 } }) }); await flush(); await flush()
  expect(front(view)).toContain(dayKey(localDay(0)))
  await act(async () => { picker().props.onChange({ detail: { value: 0 } }) }); await flush(); await flush()
  expect(front(view)).toContain(dayKey(localDay(0)))
})

it('does not reuse a selected date when the account changes and later changes back', async () => {
  const old = drive(1, -1)
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: [old], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await clickText(view, '全部时间')
  await act(async () => { view.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick() }); await flush()
  expect(front(view)).toContain(dayKey(localDay(-1)))
  const { writeAppSession } = await import('../../services/session')
  for (const userId of ['other-user', 'test-owner']) {
    writeAppSession({ setStorageSync: (key, value) => platform.values.set(key, value) },
      { apiOrigin: origin, accessToken: 'synthetic', refreshToken: null, expiresAt: null, userId, linkRequired: false })
    await act(async () => { platform.show() }); await flush(); await flush()
    expect(front(view)).toContain(dayKey(localDay(0)))
  }
})

it('does not reuse a selected date after the session generation changes', async () => {
  const old = drive(1, -1)
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: [old], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await clickText(view, '全部时间')
  await act(async () => { view.root.findByProps({ ariaLabel: '较旧日期' }).props.onClick() }); await flush()
  expect(front(view)).toContain(dayKey(localDay(-1)))
  const { matelinkApi } = await import('../../services/api')
  await act(async () => { await matelinkApi.logout() })
  const { writeAppSession } = await import('../../services/session')
  writeAppSession({ setStorageSync: (key, value) => platform.values.set(key, value) },
    { apiOrigin: origin, accessToken: 'synthetic-new-generation', refreshToken: null, expiresAt: null, userId: 'test-owner', linkRequired: false })
  await act(async () => { platform.show() }); await flush(); await flush()
  expect(front(view)).toContain(dayKey(localDay(0)))
})

it('labels fixture-only drive data without changing the normal-mode page', async () => {
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'true')
  vi.resetModules()
  const { historyCacheKey } = await import('../../services/history_cache')
  const key = historyCacheKey('drives', 'mock-fixture-user', 'mock-vehicle-uid-7', origin)
  platform.values.set(`${key}.query`, { startDate: '', endDate: '', minDistanceKm: 0 })
  const view = await mount()
  await vi.waitFor(() => expect(view.root.findAllByProps({ className: 'card fixture-data-notice' })).toHaveLength(1))
  expect(JSON.stringify(view.toJSON())).toContain('合成测试数据，未连接真实车辆')
  await vi.waitFor(() => {
    const older = view.root.findAllByProps({ ariaLabel: '较旧日期' })[0]
    expect(view.root.findAllByProps({ className: 'history-record' }).length > 0 || Boolean(older && !older.props.disabled)).toBe(true)
  })
  for (let i = 0; i < 20 && view.root.findAllByProps({ className: 'history-record' }).length === 0; i++) {
    const older = view.root.findByProps({ ariaLabel: '较旧日期' })
    if (older.props.disabled) break
    await act(async () => { older.props.onClick() }); await flush()
  }
  const record = view.root.findAllByProps({ className: 'history-record' })[0]
  expect(record).toBeDefined()
  await act(async () => { record.props.onClick() }); await flush()
  expect(view.root.findByProps({ className: 'history-detail-overlay' }).findAllByProps({ className: 'card fixture-data-notice' })).toHaveLength(1)
  expect(platform.request).not.toHaveBeenCalled()
})
