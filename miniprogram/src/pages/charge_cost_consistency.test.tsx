import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ values: new Map<string, unknown>(), request: vi.fn(), write: vi.fn(), share: vi.fn(),
  params: {} as Record<string, string>, show: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Input: 'input',
  Image: 'image', Map: 'map', ScrollView: 'scroll-view', Slider: 'slider', Switch: 'switch', Canvas: 'canvas' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => platform.values.set(key, value), removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }), navigateTo: vi.fn(), switchTab: vi.fn(),
  getFileSystemManager: () => ({ writeFileSync: platform.write }), shareFileMessage: platform.share,
  env: { USER_DATA_PATH: '/synthetic' },
}, useRouter: () => ({ params: platform.params }), useDidShow: (fn: () => void) => { platform.show = fn },
useDidHide: () => {}, useUnload: (fn: () => void) => { platform.unload = fn }, usePageScroll: () => {} }))

let tree: ReactTestRenderer | null = null
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.request.mockReset(); platform.write.mockReset(); platform.share.mockReset().mockResolvedValue({})
  platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', accessToken: 'fixture', refreshToken: null, userId: 'owner', expiresAt: null, linkRequired: false })
  const now = Date.now(), start = new Date(now - 3600000).toISOString(), end = new Date(now - 60000).toISOString()
  const charge = { charge_id: 5, session_id: 'stable-charge', source: 'teslamate_archive', source_instance_id: 'source-a',
    source_vehicle_id: 'vehicle-a', source_record_id: 'record-a', quality_state: 'observed', start_date: start, end_date: end,
    charge_energy_added: 10, cost: 0, currency_code: 'CNY', duration_min: 59 }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/snapshot')) return response({ status: {} })
    if (url.endsWith('/charges/5')) return response({ charge })
    if (url.includes('/charges?')) return response({ charges: [charge], meta: { page: 1, show: 20, total_pages: 1 } })
    if (url.includes('/drives?')) return response({ drives: [], meta: { page: 1, show: 20, total_pages: 1 } })
    throw new Error('unexpected test route')
  })
})
afterEach(async () => { if (tree) { await act(async () => { platform.unload(); tree!.unmount() }); tree = null } vi.unstubAllEnvs() })

async function mount(kind: 'charges' | 'stats' | 'cost' | 'report' | 'export') {
  platform.params = kind === 'stats' ? { kind: 'stats' } : kind === 'cost' ? { kind: 'cost' } : { kind }
  const Page = kind === 'charges' ? (await import('./charges/index')).default : kind === 'stats' ? (await import('./analysis/index')).default :
    kind === 'cost' ? (await import('./insights/index')).default : (await import('./tools/index')).default
  await act(async () => { tree = TestRenderer.create(React.createElement(Page)) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  return tree!
}
async function unmount() { await act(async () => { platform.unload(); tree!.unmount() }); tree = null }

it('keeps one labelled estimate but separates it from confirmed cost-page bill denominators', async () => {
  let page = await mount('charges')
  expect(page.root.findAllByType('text').some(node => String(node.props.className ?? '').includes('charge-row-cost') && node.children.join('') === '≈¥11.40')).toBe(true)
  await unmount()
  page = await mount('stats')
  expect(page.root.findByProps({ ariaLabel: '已知费用 1/1 条 ≈11.40 CNY' })).toBeDefined()
  expect(page.root.findAllByType('text').map(node => node.children.join('')).join('\n')).toContain('估算 1 条')
  await unmount()
  page = await mount('cost')
  expect(page.root.findByProps({ ariaLabel: '完整记录估算 ≈11.4 CNY' })).toBeDefined()
  expect(page.root.findAllByProps({ ariaLabel: '平均每度 ≈1.14 CNY/kWh' })).toHaveLength(0)
  expect(page.root.findAllByType('view').filter(node => typeof node.props.ariaLabel === 'string' &&
    node.props.ariaLabel.startsWith('已记录／手动费用'))).toHaveLength(0)
  await unmount()
  page = await mount('report')
  expect(page.root.findByProps({ ariaLabel: '1条已知费用 ≈11.40 CNY' })).toBeDefined()
  await unmount()
  page = await mount('export')
  const button = page.root.findAllByType('button').find(node => node.children.join('') === '导出文件并分享')!
  await act(async () => { await button.props.onClick() })
  const csv = platform.write.mock.calls[0][1] as string
  const columns = csv.split('\r\n')[1].split(',').map(value => value.replace(/^"|"$/g, ''))
  expect(Number(columns[7])).toBeCloseTo(11.4)
  expect(columns[11]).toBe('estimate')
  expect(Number(columns[12])).toBe(0)
  expect(platform.share).toHaveBeenCalledOnce()
})


it('shows qualified partial charge estimates separately across list, cost and cold module reopen', async () => {
  const now = Date.now(), start = new Date(now - 3600000).toISOString(), end = new Date(now - 60000).toISOString()
  const first = new Date(now - 3300000).toISOString(), last = new Date(now - 120000).toISOString()
  const charge = { charge_id: 5, session_id: 'partial-charge', source: 'telemetry_mqtt', source_instance_id: 'source-a',
    source_vehicle_id: 'vehicle-a', source_record_id: 'record-a', quality_state: 'incomplete',
    start_date: start, end_date: end, cost: null, currency_code: 'CNY', duration_min: 59,
    energy_contract: { version: 1, battery_input: { method: 'session_counter_delta', measurement_point: 'battery_input',
      source: 'telemetry_mqtt', source_field: 'DCChargingEnergyIn', quality: 'unknown', reason: 'partial_counter_window',
      start_date: start, end_date: end, observed_start_at: first, observed_end_at: last,
      time_basis: 'collector_received_at', coverage_kind: 'endpoints',
      coverage_ratio: (Date.parse(last) - Date.parse(first)) / (Date.parse(end) - Date.parse(start)), covered_energy_kwh: 10 } } }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/snapshot')) return response({ status: {} })
    if (url.endsWith('/charges/5')) return response({ charge })
    if (url.includes('/charges?')) return response({ charges: [charge], meta: { page: 1, show: 20, total_pages: 1 } })
    if (url.includes('/drives?')) throw Error('cost must not depend on drive history')
    throw Error('unexpected synthetic route')
  })
  let page = await mount('charges')
  expect(page.root.findAllByType('text').some(node => String(node.props.className ?? '').includes('charge-row-cost') && node.children.join('') === '≈¥11.40')).toBe(true)
  await unmount()
  page = await mount('cost')
  expect(page.root.findByProps({ ariaLabel: '已采集部分估算 ≈11.4 CNY' })).toBeDefined()
  const labels = page.root.findAllByType('view').map(node => node.props.ariaLabel).filter(Boolean)
  expect(labels.some(label => String(label).startsWith('已记录／手动费用'))).toBe(false)
  expect(labels.some(label => String(label).startsWith('平均每度'))).toBe(false)
  await unmount()
  vi.resetModules()
  platform.request.mockRejectedValue(new Error('synthetic offline after upgrade'))
  page = await mount('cost')
  expect(page.root.findByProps({ ariaLabel: '已采集部分估算 ≈11.4 CNY' })).toBeDefined()
})

it('efficiency analysis never invokes the unrelated charging endpoint', async () => {
  platform.params = { kind: 'efficiency' }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.includes('/drives?')) return response({ drives: [], meta: { page: 1, show: 20, total_pages: 1 } })
    if (url.includes('/charges')) throw Error('unrelated charges requested')
    throw Error('unexpected test route')
  })
  const Page = (await import('./analysis/index')).default
  await act(async () => { tree = TestRenderer.create(<Page />) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  expect(platform.request.mock.calls.some(([request]) => request.url.includes('/drives?'))).toBe(true)
  expect(platform.request.mock.calls.some(([request]) => request.url.includes('/charges'))).toBe(false)
})
