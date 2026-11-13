import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({
  values: new Map<string, unknown>(),
  request: vi.fn(),
  login: vi.fn(),
  rows: [] as Record<string, unknown>[],
  unexpected: [] as string[],
  show: (() => {}) as () => void,
  hide: (() => {}) as () => void,
  unload: (() => {}) as () => void,
}))

vi.mock('@tarojs/components', () => ({
  View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Input: 'input',
  Image: 'image', Map: 'map', ScrollView: 'scroll-view', Canvas: 'canvas', Slider: 'slider',
}))
vi.mock('@tarojs/taro', () => ({
  default: {
    request: platform.request,
    login: platform.login,
    getStorageSync: (key: string) => platform.values.get(key),
    setStorageSync: (key: string, value: unknown) => { platform.values.set(key, value) },
    removeStorageSync: (key: string) => { platform.values.delete(key) },
    getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
    navigateTo: vi.fn(), hideTabBar: vi.fn(), showTabBar: vi.fn(), pageScrollTo: vi.fn(),
  },
  useDidShow: (callback: () => void) => { platform.show = callback },
  useDidHide: (callback: () => void) => { platform.hide = callback },
  useUnload: (callback: () => void) => { platform.unload = callback },
  usePageScroll: () => {},
}))

const origin = 'https://api.example.test'
const response = (data: unknown) => ({ statusCode: 200, header: {}, data: { data } })
const charge = (id: number, startDate: string | null) => ({
  charge_id: id, session_id: `synthetic-${id}`, source: 'teslamate_archive', quality_state: 'observed',
  start_date: startDate, end_date: startDate, charge_energy_added: 2, cost: 2, currency_code: 'CNY',
})
let tree: ReactTestRenderer | null = null

class AuditBoundary extends React.Component<React.PropsWithChildren, { error: Error | null }> {
  state: { error: Error | null } = { error: null }
  static getDerivedStateFromError(error: Error) { return { error } }
  componentDidCatch(error: Error) { console.info('AUDIT page render error:', error.name, error.message) }
  render() {
    return this.state.error ? <text className="audit-render-error">{this.state.error.message}</text> : this.props.children
  }
}

beforeEach(async () => {
  vi.resetModules()
  vi.stubEnv('TARO_APP_API_BASE_URL', origin)
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'false')
  platform.values.clear()
  platform.request.mockReset()
  platform.login.mockReset().mockImplementation(() => { throw new Error('Unexpected WeChat login') })
  platform.rows = []
  platform.unexpected = []
  platform.values.set('matelink.wechat.session.v1', {
    apiOrigin: origin, accessToken: 'synthetic-only', refreshToken: null,
    expiresAt: null, userId: 'synthetic-owner', linkRequired: false,
  })
  const { historyCacheKey } = await import('../src/services/history_cache')
  const key = historyCacheKey('charges', 'synthetic-owner', 'uid7', origin)
  platform.values.set(`${key}.query`, { startDate: '', endDate: '', minDistanceKm: 0 })
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    const parsed = new URL(url)
    if (parsed.origin !== origin) {
      platform.unexpected.push(parsed.origin)
      throw new Error('Unexpected request origin')
    }
    if (parsed.pathname === '/api/v1/cars') return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7', name: 'Synthetic' }] })
    if (parsed.pathname === '/api/v1/cars/7/charges/current') return response({ charge: null })
    if (parsed.pathname === '/api/v1/cars/7/charges') return response({
      charges: platform.rows, meta: { page: 1, show: 20, total_pages: 1 },
    })
    if (parsed.pathname === '/api/matelink/v1/cars/7/snapshot') return response({ status: {} })
    platform.unexpected.push(parsed.pathname)
    throw new Error('Unexpected synthetic request path')
  })
})

afterEach(async () => {
  if (tree) {
    await act(async () => { platform.unload(); tree!.unmount() })
    tree = null
  }
  expect(platform.unexpected).toEqual([])
  expect(platform.login).not.toHaveBeenCalled()
  vi.unstubAllEnvs()
})

async function normalizedCharges() {
  const { matelinkApi } = await import('../src/services/api')
  const cars = await matelinkApi.getCars()
  expect(cars).toHaveLength(1)
  const result = await matelinkApi.getCharges(7, 1, 20, cars[0])
  console.info('AUDIT normalized charges:', JSON.stringify(result.items.map(row => ({ id: row.id, startDate: row.startDate, quality: row.qualityState }))))
  return result.items
}

async function mountChargesPage() {
  const previousCalls = platform.request.mock.calls.length
  const Page = (await import('../src/pages/charges/index')).default
  await act(async () => { tree = TestRenderer.create(<AuditBoundary><Page /></AuditBoundary>) })
  await act(async () => { platform.show() })
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) })
  const pagePaths = platform.request.mock.calls.slice(previousCalls).map(([options]) => new URL(options.url).pathname)
  console.info('AUDIT page request paths:', JSON.stringify(pagePaths))
  expect(pagePaths).toContain('/api/v1/cars')
  expect(pagePaths).toContain('/api/v1/cars/7/charges')
  return tree!
}

function cellLabels(page: ReactTestRenderer) {
  return page.root.findAllByType('button').filter(node => node.props.className?.includes('charge-activity-cell'))
    .map(node => String(node.props.ariaLabel))
}
function shown(page: ReactTestRenderer) {
  return page.root.findAllByType('text').map(node => node.children.join('')).join('\n')
}

it('keeps an API date-only charge on its civil day in the actual LA page', async () => {
  platform.rows = [charge(1, '2026-11-09')]
  const rows = await normalizedCharges()
  expect(rows.map(row => row.startDate)).toEqual(['2026-11-09'])
  const page = await mountChargesPage()
  const labels = cellLabels(page)
  console.info('AUDIT date-only page cells:', JSON.stringify(labels))
  expect(labels.some(label => label.startsWith('2026-11-09，'))).toBe(true)
  expect(page.root.findByProps({ className: 'location-date' }).children.join('')).toBe('2026-11-09')
})

it('renders a valid charge safely when the API also returns an invalid civil date', async () => {
  platform.rows = [charge(1, '2026-03-03T12:00:00Z'), { ...charge(2, '2026-02-30T12:00:00Z'), currency_code: 'USD' }]
  const rows = await normalizedCharges()
  console.info('AUDIT invalid date passed normalization:', rows.some(row => row.startDate === '2026-02-30T12:00:00Z'))
  expect(rows.some(row => row.startDate === '2026-03-03T12:00:00Z')).toBe(true)
  const page = await mountChargesPage()
  const errors = page.root.findAllByProps({ className: 'audit-render-error' }).map(node => node.children.join(''))
  console.info('AUDIT invalid-date page errors:', JSON.stringify(errors))
  expect(errors).toEqual([])
  const labels = cellLabels(page)
  console.info('AUDIT invalid-date page cells:', JSON.stringify(labels))
  expect(labels.some(label => label.startsWith('2026-03-03，'))).toBe(true)
  expect(labels.some(label => label.startsWith('2026-03-02，'))).toBe(false)
  expect(shown(page)).toContain('另有 1 条记录日期无法确认，未计入日格')
  expect(shown(page)).toContain('统计已返回的 1 条记录')
  expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(page.root.findByProps({ ariaLabel: '充电次数 2 次' })).toBeDefined()
  expect(page.root.findAllByProps({ className: 'location-date' }).map(node => node.children.join(''))).toContain('暂无日期')
  const { historyCacheKey, readHistoryCache } = await import('../src/services/history_cache')
  const key = historyCacheKey('charges', 'synthetic-owner', 'uid7', origin)
  const cached = readHistoryCache<{ startDate: string | null }>({ getStorageSync: name => platform.values.get(name), setStorageSync: (name, value) => { platform.values.set(name, value) } }, key)
  expect(cached.map(row => row.startDate)).toContain('2026-02-30T12:00:00Z')
  const cost = page.root.findAllByType('button').find(node => node.children.join('') === '费用')!
  await act(async () => { cost.props.onClick() })
  expect(cellLabels(page)[0]).toContain('2.00 CNY')
})

it.each(['2026-02-30 12:00:00', '2026-02-30T12:00:00Z', '2026-02-30t12:00:00Z',
  '2026-2-30', '2026/02/30', ' 2026/2/30 12:00:00 ', '2026-002-030',
  '2/30/2026', ' 2/30/2026 12:00:00 ', '02-29-2025'])(
  'keeps invalid API date %s out of the charge day grid', async invalidDate => {
    platform.rows = [charge(1, '2026-03-03'), charge(2, invalidDate)]
    const rows = await normalizedCharges()
    expect(rows.some(row => row.startDate === invalidDate)).toBe(true)
    const page = await mountChargesPage()
    expect(cellLabels(page)).toEqual([expect.stringMatching(/^2026-03-03，/)])
    expect(shown(page)).toContain('另有 1 条记录日期无法确认，未计入日格')
    expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  },
)

it.each([
  ['2026-2-3', '2026-02-03'],
  ['2026/02/28', '2026-02-28'],
  [' 2024/2/29 12:00:00 ', '2024-02-29'],
  ['2/28/2026', '2026-02-28'],
  ['02-29-2024', '2024-02-29'],
])('keeps valid legacy API date %s on its existing local day', async (date, expectedDay) => {
  platform.rows = [charge(1, date)]
  const rows = await normalizedCharges()
  expect(rows[0].startDate).toBe(date)
  const page = await mountChargesPage()
  expect(cellLabels(page).some(label => label.startsWith(`${expectedDay}，`))).toBe(true)
  expect(shown(page)).not.toContain('日期无法确认')
})

it('shows unclassified coverage when every returned charge has an unknown or impossible date', async () => {
  platform.rows = [charge(1, null), charge(2, '2026-02-30T12:00:00Z')]
  await normalizedCharges()
  const page = await mountChargesPage()
  expect(page.root.findAllByProps({ className: 'audit-render-error' })).toHaveLength(0)
  expect(cellLabels(page)).toEqual([])
  expect(shown(page)).toContain('另有 2 条记录日期无法确认，未计入日格')
  expect(page.root.findAllByProps({ className: 'history-record' })).toHaveLength(2)
  expect(page.root.findByProps({ ariaLabel: '充电次数 2 次' })).toBeDefined()
})

it.each([
  ['both boundaries', '2026-11-09', '2026-11-09'],
  ['start boundary only', '2026-11-09', ''],
  ['end boundary only', '', '2026-11-09'],
])('keeps a date-only charge and explains unclassified range membership with %s', async (_label, startDate, endDate) => {
  const { historyCacheKey } = await import('../src/services/history_cache')
  const key = historyCacheKey('charges', 'synthetic-owner', 'uid7', origin)
  platform.values.set(`${key}.query`, { startDate, endDate, minDistanceKm: 0 })
  platform.rows = [charge(1, '2026-11-09'), charge(2, '2026-02-30T12:00:00Z')]
  await normalizedCharges()
  const page = await mountChargesPage()
  expect(cellLabels(page).some(label => label.startsWith('2026-11-09，'))).toBe(true)
  expect(shown(page)).toContain('另有 1 条记录日期无法确认，无法判断是否属于当前日期范围，未计入日格')
  expect(page.root.findByProps({ ariaLabel: '充电次数 1 次' })).toBeDefined()
})
