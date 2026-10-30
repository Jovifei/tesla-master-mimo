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

it('uses one labelled TeslaMate-zero estimate across charge, statistics, cost, report and export', async () => {
  let page = await mount('charges')
  expect(page.root.findByProps({ ariaLabel: '充电费用 ≈¥11.40' })).toBeDefined()
  await unmount()
  page = await mount('stats')
  expect(page.root.findByProps({ ariaLabel: '已知费用 1/1 条 ≈11.40 CNY' })).toBeDefined()
  expect(page.root.findAllByType('text').map(node => node.children.join('')).join('\n')).toContain('估算 1 条')
  await unmount()
  page = await mount('cost')
  expect(page.root.findByProps({ ariaLabel: '费用合计 ≈11.4 CNY' })).toBeDefined()
  expect(page.root.findByProps({ ariaLabel: '配对单位电量费用 ≈1.14 CNY/kWh' })).toBeDefined()
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
