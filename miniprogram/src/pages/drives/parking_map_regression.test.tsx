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
  setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) },
  removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
  hideTabBar: vi.fn(), showTabBar: vi.fn(), navigateTo: vi.fn(),
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn },
  useUnload: (fn: () => void) => { platform.unload = fn }, usePageScroll: () => {} }))

let page: ReactTestRenderer | null = null
const origin = 'https://api.example.test'
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
const car = { car_id: 7, vehicle_uid: 'drive-audit' }
const now = new Date()
// Keep the synthetic adjacent drives on one local day and safely before now.
const base = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1, 12).getTime()
const at = (minutes: number) => new Date(base + minutes * 60_000).toISOString()
const drive = (id: number, minutes: number, km: number, quality = 'observed') => ({ drive_id: id, session_id: `drive-${id}`,
  source: 'teslamate_archive', source_instance_id: 'archive-A', source_vehicle_id: 'car-A', source_record_id: `record-${id}`,
  quality_state: quality, start_date: at(minutes), end_date: at(minutes + 10), duration_min: 10,
  odometer_details: { odometer_distance: km }, battery_details: { start_battery_level: 80, end_battery_level: 79 } })

async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function selectFixtureDay(view: ReactTestRenderer) {
  const older = view.root.findAllByProps({ ariaLabel: '较旧日期' }).find(button => !button.props.disabled)
  if (older) { await act(async () => { older.props.onClick() }); await flush() }
}
async function mount() {
  const { writeAppSession } = await import('../../services/session')
  writeAppSession({ setStorageSync: (key, value) => platform.values.set(key, value) },
    { apiOrigin: origin, accessToken: 'synthetic', refreshToken: null, expiresAt: null, userId: 'test-owner', linkRequired: false })
  const Page = (await import('./index')).default
  await act(async () => { page = TestRenderer.create(<Page />) })
  await act(async () => { platform.show() }); await flush(); await flush()
  await selectFixtureDay(page!)
  return page!
}
beforeEach(() => { vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', origin); platform.values.clear(); platform.request.mockReset() })
afterEach(async () => { if (page) await act(async () => { platform.unload(); page!.unmount() }); page = null; vi.unstubAllEnvs() })

it.each(['short', 'quarantined'] as const)('does not bridge a hidden %s drive on the real page', async kind => {
  const records = [drive(3, 120, 2), drive(2, 60, kind === 'short' ? 0.1 : 2, kind === 'short' ? 'observed' : 'quarantined'), drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
  expect(view.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(JSON.stringify(view.toJSON())).not.toContain('来源身份未确认的历史记录已保留')
})

it('does not request or display a parked proof across a date-only middle drive', async () => {
  const records = [drive(3, 120, 2), { ...drive(2, 60, 2), start_date: at(60).slice(0, 10) }, drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  expect(platform.request.mock.calls.some(([request]) => request.url.endsWith('/parked/1/3'))).toBe(false)
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
})

it('reports retained ambiguous legacy history without showing or counting it', async () => {
  const current = drive(1, 0, 2)
  const { normalizeDrive } = await import('../../services/api')
  const { historyCacheKey, readHistoryCache, writeHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key),
    setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
  const key = historyCacheKey('drives', 'test-owner', 'drive-audit', origin)
  const legacy = normalizeDrive({ ...current, source_instance_id: undefined, source_vehicle_id: undefined,
    source_record_id: undefined, start_address: 'Old unconfirmed place' })!
  expect(writeHistoryCache(storage, key, [legacy])).toBe(true)
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: [current], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  const text = JSON.stringify(view.toJSON())
  expect(text).toContain('来源身份未确认的历史记录已保留，但未计入当前展示或统计')
  expect(text).not.toContain('Old unconfirmed place')
  expect(view.root.findAllByProps({ className: 'history-record' })).toHaveLength(1)
  expect(view.root.findByProps({ ariaLabel: '总行程数 1 次' })).toBeDefined()
  expect(readHistoryCache<{ identityAmbiguous?: boolean }>(storage, key).some(row => row.identityAmbiguous === true)).toBe(true)
  const longTrips = view.root.findAllByType('button').find(item => item.children.join('') === '长途 (100 km+)')!
  await act(async () => { longTrips.props.onClick() }); await flush()
  expect(JSON.stringify(view.toJSON())).toContain('来源身份未确认的历史记录已保留，但未计入当前展示或统计')
})

it('shows a parked card when a complete, same-instance history proves two adjacent drives', async () => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/parked/1/3')) return response({ older_drive_id: 1, newer_drive_id: 3,
      start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive' })
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  expect(platform.request.mock.calls.some(([request]) => request.url.endsWith('/parked/1/3'))).toBe(true)
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(1)
  const calls = platform.request.mock.calls.filter(([request]) => request.url.endsWith('/parked/1/3')).length
  await act(async () => { view.root.findByProps({ className: 'history-record history-parked' }).props.onClick() }); await flush()
  expect(view.root.findByProps({ ariaLabel: '驻车详情' })).toBeDefined()
  expect(platform.request.mock.calls.filter(([request]) => request.url.endsWith('/parked/1/3'))).toHaveLength(calls)
})

it('reuses a confirmed proof after filtering the candidate out and back into view', async () => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/parked/1/3')) return response({ older_drive_id: 1, newer_drive_id: 3,
      start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive' })
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  const click = async (label: string) => {
    const button = view.root.findAllByType('button').find(item => item.children.join('') === label)
    expect(button).toBeDefined()
    await act(async () => { button!.props.onClick() }); await flush()
  }
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(1)
  await click('长途 (100 km+)')
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
  await click('通勤 (< 10 km)')
  await selectFixtureDay(view)
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(1)
  expect(platform.request.mock.calls.filter(([request]) => request.url.endsWith('/parked/1/3'))).toHaveLength(1)
})

it('uses verified parked SOC boundaries together with the verified delta, not stale list fields', async () => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/parked/1/3')) return response({ older_drive_id: 1, newer_drive_id: 3,
      start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive',
      address: 'Verified stop', start_battery_level: 70, end_battery_level: 68, battery_delta: -2 })
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  const card = view.root.findByProps({ className: 'history-record history-parked' })
  expect(card.findByProps({ className: 'parked-soc' }).children.join('')).toBe('70% → 68%')
  await act(async () => { card.props.onClick() }); await flush()
  const detail = view.root.findByProps({ ariaLabel: '驻车详情' })
  expect(detail.findByProps({ ariaLabel: '起始电量 70%' })).toBeDefined()
  expect(detail.findByProps({ ariaLabel: '结束电量 68%' })).toBeDefined()
  expect(detail.findByProps({ ariaLabel: '电量变化 -2%' })).toBeDefined()
  expect(detail.findAllByType('text').some(item => item.children.join('').includes('Verified stop'))).toBe(true)
})

it('does not replace missing verified parked SOC with old list values', async () => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  const { normalizeDrive } = await import('../../services/api')
  const { historyCacheKey, writeHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key),
    setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
  expect(writeHistoryCache(storage, historyCacheKey('drives', 'test-owner', 'drive-audit', origin),
    [normalizeDrive({ ...records[1], end_address: 'Old cached address' })!])).toBe(true)
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/parked/1/3')) return response({ older_drive_id: 1, newer_drive_id: 3,
      start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive' })
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  const card = view.root.findByProps({ className: 'history-record history-parked' })
  expect(card.findAllByProps({ className: 'parked-soc' })).toHaveLength(0)
  expect(card.findByProps({ className: 'parked-title' }).children.join('')).toBe('驻车于 未知位置')
  await act(async () => { card.props.onClick() }); await flush()
  const detail = view.root.findByProps({ ariaLabel: '驻车详情' })
  expect(detail.findByProps({ ariaLabel: '起始电量 暂无数据' })).toBeDefined()
  expect(detail.findByProps({ ariaLabel: '结束电量 暂无数据' })).toBeDefined()
  expect(detail.findByProps({ ariaLabel: '电量变化 暂无数据' })).toBeDefined()
  expect(detail.findByProps({ className: 'parked-detail-hero-title' }).children.join('')).toBe('驻车于 未知位置')
})

it('retains an address from the current proven list when the detail has no address', async () => {
  const records = [drive(3, 120, 2), { ...drive(1, 0, 2), end_address: 'Fresh list address' }]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/parked/1/3')) return response({ older_drive_id: 1, newer_drive_id: 3,
      start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive' })
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  const card = view.root.findByProps({ className: 'history-record history-parked' })
  expect(card.findByProps({ className: 'parked-title' }).children.join('')).toBe('驻车于 Fresh list address')
  await act(async () => { card.props.onClick() }); await flush()
  expect(view.root.findByProps({ className: 'parked-detail-hero-title' }).children.join('')).toBe('驻车于 Fresh list address')
})

it.each([[71, '电量 +1%'], [70, '电量未变化']] as const)('does not infer charging or consumption from SOC change to %s', async (endSoc, expected) => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/parked/1/3')) return response({ older_drive_id: 1, newer_drive_id: 3,
      start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive',
      start_battery_level: 70, end_battery_level: endSoc, battery_delta: endSoc - 70 })
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record history-parked' }).props.onClick() }); await flush()
  const detail = view.root.findByProps({ ariaLabel: '驻车详情' })
  expect(detail.findAllByType('text').some(item => item.children.join('').includes(expected))).toBe(true)
})

it.each(['unavailable', 'forbidden', 'network'] as const)('keeps a full-list candidate hidden when exact parked proof is %s', async status => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/parked/1/3')) {
      if (status === 'network') throw new Error('offline')
      return { statusCode: status === 'forbidden' ? 403 : 404, data: { error: status === 'forbidden' ? 'forbidden' : 'parked_history_bounds_unavailable' }, header: {} }
    }
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  expect(view.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
  expect(platform.request.mock.calls.some(([request]) => request.url.endsWith('/parked/1/3'))).toBe(true)
})

it('does not show a late old-vehicle parked proof after changing vehicles', async () => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  let release!: (value: unknown) => void
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car, { car_id: 8, vehicle_uid: 'drive-other' }] }))
    if (url.endsWith('/cars/7/parked/1/3')) return new Promise(resolve => { release = resolve })
    if (url.includes('/cars/8/drives')) return Promise.resolve(response({ drives: [], meta: { page: 1, show: 20, total_pages: 1 } }))
    return Promise.resolve(response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  let view = await mount()
  expect(release).toBeTypeOf('function')
  // App has no car switcher; reseed the persisted selection and remount.
  platform.values.set('matelink.selected.car.v1.test-owner', 'drive-other')
  await act(async () => { platform.unload(); view.unmount() })
  view = await mount()
  await act(async () => { release(response({ older_drive_id: 1, newer_drive_id: 3,
    start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive' })) }); await flush()
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
  expect(JSON.stringify(view.toJSON())).toContain('未找到行程')
})

it('withholds parked inference while later history pages have not yet been received', async () => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.includes('page=2')) return new Promise(() => {})
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 2 } })
  })
  const view = await mount()
  expect(view.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
})

it('does not retain online parking proof after reopening the detail offline', async () => {
  const records = [drive(3, 120, 2), drive(1, 0, 2)]
  let offline = false
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (offline) throw new Error('offline')
    if (url.endsWith('/cars')) return response({ cars: [car] })
    if (url.endsWith('/drives/3')) return response({ drive: records[0] })
    if (url.endsWith('/parked/1/3')) return response({ older_drive_id: 1, newer_drive_id: 3,
      start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive' })
    return response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } })
  })
  const view = await mount()
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(1)
  await act(async () => { view.root.findAllByProps({ className: 'history-record' })[0].props.onClick() }); await flush()
  offline = true
  await act(async () => { platform.hide(); platform.show() }); await flush(); await flush()
  expect(JSON.stringify(view.toJSON())).toContain('在线车辆暂时无法重新验证')
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
})

it('withholds parked inference if a malformed raw history row was dropped', async () => {
  const records = [drive(3, 120, 2), {}, drive(1, 0, 2)]
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  expect(view.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
})

it('does not use a cached end time to prove adjacency when the current server row omits it', async () => {
  const older = drive(1, 0, 2), newer = drive(3, 120, 2)
  const { end_date: _omitted, ...sparseOlder } = older
  const { normalizeDrive } = await import('../../services/api')
  const { historyCacheKey, writeHistoryCache } = await import('../../services/history_cache')
  const storage = { getStorageSync: (key: string) => platform.values.get(key),
    setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) }, getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) }
  expect(writeHistoryCache(storage, historyCacheKey('drives', 'test-owner', 'drive-audit', origin), [normalizeDrive(older)!])).toBe(true)
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    response({ drives: [newer, sparseOlder], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  expect(view.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
})

it('requires a known shared source instance and source vehicle before pairing', async () => {
  const original = [drive(3, 120, 2), drive(1, 0, 2)]
  for (const records of [original.map(({ source_instance_id: _instance, ...row }) => row),
    original.map(({ source_vehicle_id: _vehicle, ...row }) => row),
    [original[0], { ...original[1], source_vehicle_id: 'another-source-car' }]]) {
    platform.values.clear()
    platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
      response({ drives: records, meta: { page: 1, show: 20, total_pages: 1 } }))
    const view = await mount()
    expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
    await act(async () => { platform.unload(); view.unmount() }); page = null
  }
})

it('does not bridge route samples when only isolated coordinates exist', async () => {
  const item = drive(1, 0, 2)
  const detail = { ...item, drive_details: [
    { date: at(0), latitude: 39.9, longitude: 116.4 },
    { date: at(5), latitude: null, longitude: null },
    { date: at(10), latitude: 39.91, longitude: 116.41 },
  ] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    url.endsWith('/drives/1') ? response({ drive: detail }) : response({ drives: [item], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush(); await flush()
  const map = view.root.findByType('map' as React.ElementType)
  expect(map.props.polyline).toEqual([])
})

it('does not show route evidence returned for a different source instance with the same public ID', async () => {
  const item = drive(1, 0, 2)
  const other = { ...item, source_instance_id: 'archive-B', drive_details: [
    { date: at(0), latitude: 39.9, longitude: 116.4 }, { date: at(10), latitude: 39.91, longitude: 116.41 },
  ] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    url.endsWith('/drives/1') ? response({ drive: other }) : response({ drives: [item], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush(); await flush()
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
})

it('keeps elevation and ambient temperature while rendering qualified same-record curves inline', async () => {
  const item = { ...drive(1, 0, 2), outside_temp_avg: 23 }
  const detail = { ...item, drive_details: [
    { date: at(0), speed: 0, power: 0, battery_level: 80, elevation: 100 },
    { date: at(10), speed: 40, power: -4, battery_level: 79, elevation: 110 },
  ] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    url.endsWith('/drives/1') ? response({ drive: detail }) : response({ drives: [item], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush(); await flush()
  expect(view.root.findByProps({ ariaLabel: '车外温度 23 °C' })).toBeDefined()
  expect(view.root.findByProps({ ariaLabel: '累计爬升 +10 m' })).toBeDefined()
  for (const id of ['drive-inline-speed', 'drive-inline-power', 'drive-inline-soc']) expect(view.root.findByProps({ id })).toBeDefined()
})


it('does not show wrong-window evidence when the record detail fails', async () => {
  const item = drive(1, 0, 2)
  const otherWindow = { ...drive(1, 30, 2), drive_details: [
    { date: at(30), latitude: 39.9, longitude: 116.4, speed: 10, power: 25, battery_level: 80, elevation: 100 },
    { date: at(40), latitude: 39.91, longitude: 116.41, speed: 20, power: 30, battery_level: 79, elevation: 110 },
  ] }
  const pending: ((value: unknown) => void)[] = []
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (url.endsWith('/drives/1')) return new Promise(resolve => { pending.push(resolve) })
    return Promise.resolve(response({ drives: [item], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
  expect(pending).toHaveLength(2)
  await act(async () => { pending[0](response({ drive: otherWindow })) }); await flush()
  expect(view.root.findAllByProps({ id: 'drive-inline-power' })).toHaveLength(0)
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
  await act(async () => { pending[1]({ statusCode: 503, data: { error: 'unavailable' }, header: {} }) }); await flush()
  expect(view.root.findAllByProps({ id: 'drive-inline-power' })).toHaveLength(0)
})


const windowEvidence = (minutes: number) => ({ ...drive(1, minutes, 2), drive_details: [
  { date: at(minutes), latitude: 39.9, longitude: 116.4, speed: 10, power: 25, battery_level: 80, elevation: 100 },
  { date: at(minutes + 10), latitude: 39.91, longitude: 116.41, speed: 20, power: 30, battery_level: 79, elevation: 110 },
] })
async function mountWindowRace() {
  const item = drive(1, 0, 2), pending: ((value: unknown) => void)[] = []
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (url.endsWith('/drives/1')) return new Promise(resolve => { pending.push(resolve) })
    return Promise.resolve(response({ drives: [item], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush()
  expect(pending).toHaveLength(2)
  return { view, pending }
}
async function releaseDrive(pending: ((value: unknown) => void)[], index: number, detail: unknown) {
  await act(async () => { pending[index](response({ drive: detail })) }); await flush()
}
function expectNoDriveSamples(view: ReactTestRenderer) {
  for (const id of ['drive-inline-speed', 'drive-inline-power', 'drive-inline-soc']) expect(view.root.findAllByProps({ id })).toHaveLength(0)
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(0)
  expect(view.root.findAllByProps({ ariaLabel: '累计爬升 +10 m' })).toHaveLength(0)
}

it.each(['evidence-first', 'detail-first'] as const)('hides old-window samples after the selected window changes, %s', async order => {
  const { view, pending } = await mountWindowRace()
  if (order === 'evidence-first') {
    await releaseDrive(pending, 0, windowEvidence(0))
    expect(view.root.findAllByProps({ id: 'drive-inline-power' }).length).toBeGreaterThan(0)
    await releaseDrive(pending, 1, drive(1, 30, 2))
  } else {
    await releaseDrive(pending, 1, drive(1, 30, 2))
    await releaseDrive(pending, 0, windowEvidence(0))
  }
  expectNoDriveSamples(view)
})

it.each(['evidence-first', 'detail-first'] as const)('shows matching evidence after a corrected record arrives, %s', async order => {
  const { view, pending } = await mountWindowRace()
  if (order === 'evidence-first') {
    await releaseDrive(pending, 0, windowEvidence(30))
    expectNoDriveSamples(view)
    await releaseDrive(pending, 1, drive(1, 30, 2))
  } else {
    await releaseDrive(pending, 1, drive(1, 30, 2))
    await releaseDrive(pending, 0, windowEvidence(30))
  }
  for (const id of ['drive-inline-speed', 'drive-inline-power', 'drive-inline-soc']) expect(view.root.findAllByProps({ id }).length).toBeGreaterThan(0)
  expect(view.root.findAllByType('map' as React.ElementType)).toHaveLength(1)
})

it('admits equivalent timezone instants but rejects a nanosecond window mismatch', async () => {
  const { view, pending } = await mountWindowRace()
  const original = windowEvidence(0)
  const offset = (value: string) => new Date(Date.parse(value) + 8 * 3_600_000).toISOString().replace('Z', '+08:00')
  await releaseDrive(pending, 0, { ...original, start_date: offset(original.start_date), end_date: offset(original.end_date) })
  expect(view.root.findAllByProps({ id: 'drive-inline-power' }).length).toBeGreaterThan(0)
  await releaseDrive(pending, 1, { ...drive(1, 0, 2), end_date: at(10).replace('.000Z', '.000000001Z') })
  expectNoDriveSamples(view)
})

it('rejects a date-only record window even if its identity and sample dates match', async () => {
  const item = { ...drive(1, 0, 2), start_date: at(0).slice(0, 10) }
  const detail = { ...windowEvidence(0), start_date: item.start_date }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    url.endsWith('/drives/1') ? response({ drive: detail }) : response({ drives: [item], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush(); await flush()
  expectNoDriveSamples(view)
})


it('keeps matching evidence if the separate record-detail request fails', async () => {
  const { view, pending } = await mountWindowRace()
  await releaseDrive(pending, 0, windowEvidence(0))
  await act(async () => { pending[1]({ statusCode: 503, data: { error: 'unavailable' }, header: {} }) }); await flush()
  expect(view.root.findAllByProps({ id: 'drive-inline-power' }).length).toBeGreaterThan(0)
})

it.each([
  ['positive submillisecond', '.000000001Z', '.000000002Z'],
  ['equal', '.000000001Z', '.000000001Z'],
  ['reversed', '.000000002Z', '.000000001Z'],
] as const)('does not draw an unrepresentable %s window', async (_label, from, to) => {
  const start = at(0).replace('.000Z', from), end = at(0).replace('.000Z', to)
  const item = { ...drive(1, 0, 2), start_date: start, end_date: end }
  const detail = { ...item, drive_details: [
    { date: start, speed: 1, power: 10, battery_level: 80, latitude: 39.9, longitude: 116.4 },
    { date: end, speed: 2, power: 11, battery_level: 79, latitude: 39.91, longitude: 116.41 },
  ] }
  platform.request.mockImplementation(async ({ url }: { url: string }) => url.endsWith('/cars') ? response({ cars: [car] }) :
    url.endsWith('/drives/1') ? response({ drive: detail }) : response({ drives: [item], meta: { page: 1, show: 20, total_pages: 1 } }))
  const view = await mount()
  await act(async () => { view.root.findByProps({ className: 'history-record' }).props.onClick() }); await flush(); await flush()
  expectNoDriveSamples(view)
})


it('does not display a late prior selection after opening another record', async () => {
  const first = drive(1, 0, 2), second = drive(2, 30, 2)
  const pending: Record<string, ((value: unknown) => void)[]> = { '1': [], '2': [] }
  platform.request.mockImplementation(({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return Promise.resolve(response({ cars: [car] }))
    if (url.endsWith('/drives/1')) return new Promise(resolve => { pending['1'].push(resolve) })
    if (url.endsWith('/drives/2')) return new Promise(resolve => { pending['2'].push(resolve) })
    if (url.includes('/parked/')) return Promise.resolve({ statusCode: 404, data: { error: 'parked_history_bounds_unavailable' }, header: {} })
    return Promise.resolve(response({ drives: [second, first], meta: { page: 1, show: 20, total_pages: 1 } }))
  })
  const view = await mount()
  const cards = view.root.findAllByProps({ className: 'history-record' })
  await act(async () => { cards[1].props.onClick() }); await flush()
  await act(async () => { cards[0].props.onClick() }); await flush()
  expect(pending['1']).toHaveLength(2); expect(pending['2']).toHaveLength(2)
  await releaseDrive(pending['1'], 0, windowEvidence(0))
  await releaseDrive(pending['1'], 1, first)
  expectNoDriveSamples(view)
  await releaseDrive(pending['2'], 0, { ...windowEvidence(30), drive_id: 2, session_id: 'drive-2', source_record_id: 'record-2' })
  await releaseDrive(pending['2'], 1, second)
  expect(view.root.findAllByProps({ id: 'drive-inline-power' }).length).toBeGreaterThan(0)
})
