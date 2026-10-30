import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({
  request: vi.fn(), values: new Map<string, unknown>(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void,
}))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Image: 'image', Map: 'map', ScrollView: 'scroll-view' }))
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
const base = Date.now() - 4 * 60 * 60 * 1000
const at = (minutes: number) => new Date(base + minutes * 60_000).toISOString()
const drive = (id: number, minutes: number, km: number, quality = 'observed') => ({ drive_id: id, session_id: `drive-${id}`,
  source: 'teslamate_archive', source_instance_id: 'archive-A', source_vehicle_id: 'car-A', source_record_id: `record-${id}`,
  quality_state: quality, start_date: at(minutes), end_date: at(minutes + 10), duration_min: 10,
  odometer_details: { odometer_distance: km }, battery_details: { start_battery_level: 80, end_battery_level: 79 } })

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
  const longTrips = view.root.findAllByType('button').find(item => item.children.join('') === '长途 (100km+)')!
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
  await click('长途 (100km+)')
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
  await click('通勤 (< 10km)')
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
  const view = await mount()
  expect(release).toBeTypeOf('function')
  const picker = view.root.findAllByType('picker' as React.ElementType).find(item => item.props.mode === 'selector' && item.props.range?.length === 2)
  expect(picker).toBeDefined()
  await act(async () => { picker!.props.onChange({ detail: { value: 1 } }) }); await flush()
  await act(async () => { release(response({ older_drive_id: 1, newer_drive_id: 3,
    start_date: records[1].end_date, end_date: records[0].start_date, source: 'teslamate_archive' })) }); await flush()
  expect(view.root.findAllByProps({ className: 'history-record history-parked' })).toHaveLength(0)
  expect(JSON.stringify(view.toJSON())).toContain('暂无可展示的行程')
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
