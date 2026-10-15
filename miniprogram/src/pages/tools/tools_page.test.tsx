import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), params: { kind: 'report' } as Record<string, string>,
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void,
  write: vi.fn(), share: vi.fn(), open: vi.fn(), toast: vi.fn() }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Input: 'input', Picker: 'picker', Map: 'map', Slider: 'slider', Switch: 'switch' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, value),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }), env: { USER_DATA_PATH: '/synthetic' },
  getFileSystemManager: () => ({ writeFileSync: platform.write }), openDocument: platform.open, shareFileMessage: platform.share, showToast: platform.toast,
}, useRouter: () => ({ params: platform.params }), useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn }, useUnload: (fn: () => void) => { platform.unload = fn } }))
let renderer: ReactTestRenderer | null = null
const start = '2026-01-01T00:00:00Z', end = '2026-01-01T00:30:00Z'
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test'); platform.values.clear(); platform.request.mockReset(); platform.write.mockReset(); platform.share.mockReset(); platform.open.mockReset()
  platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', accessToken: 'synthetic', userId: 'a', refreshToken: null, expiresAt: null, linkRequired: false })
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'tools-car' }] })
    if (url.endsWith('/drives/1')) return response({ drive: { drive_id: 1, start_date: start, end_date: end, source: 'teslamate_archive', quality_state: 'observed',
      odometer_details: { odometer_distance: 10 }, energy_consumed_net: -.5, drive_details: [{ date: start, latitude: 39.9, longitude: 116.4 }, { date: end, latitude: 39.91, longitude: 116.41 }] } })
    if (url.includes('/drives')) return { ...response({ drives: [{ drive_id: 1, start_date: start, end_date: end, source: 'teslamate_archive', quality_state: 'observed',
      odometer_details: { odometer_distance: 10 }, energy_consumed_net: -.5 }] }), data: { data: { drives: [{ drive_id: 1, start_date: start, end_date: end, source: 'teslamate_archive', quality_state: 'observed', odometer_details: { odometer_distance: 10 }, energy_consumed_net: -.5 }] }, meta: { page: 1, show: 20, total: 1 } } }
    return { ...response({ charges: [] }), data: { data: { charges: [] }, meta: { page: 1, show: 20, total: 0 } } }
  })
})
afterEach(async () => { if (renderer) await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null; vi.unstubAllEnvs() })
async function mount(kind: string, extra: Record<string, string> = {}) {
  platform.params = { kind, ...extra }
  const Page = (await import('./index')).default
  await act(async () => { renderer = TestRenderer.create(<Page />) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
}
const text = () => JSON.stringify(renderer?.toJSON())
async function click(label: string) { const button = renderer!.root.findAllByType('button').find(node => node.children.join('') === label)!; expect(button).toBeDefined(); await act(async () => { await button.props.onClick(); await new Promise(resolve => setTimeout(resolve, 0)) }) }
it('annual report uses actual parsed history and exports a local valid PDF, not a fake complete history', async () => {
  await mount('report')
  await act(async () => { renderer!.root.findByType('picker' as React.ElementType).props.onChange({ detail: { value: '2026' } }) })
  expect(text()).toContain('配对平均能耗')
  expect(text()).toContain('-5 kWh/100km')
  await click('导出年度 PDF')
  const data = platform.write.mock.calls[0][1] as ArrayBuffer
  expect(String.fromCharCode(...new Uint8Array(data).slice(0, 8))).toBe('%PDF-1.4')
  expect(platform.open).toHaveBeenCalledOnce()
})
it('unbound/foreign sessions do not create a zero-valued report or export any file', async () => {
  platform.values.set('matelink.wechat.session.v1', { accessToken: 'private', apiOrigin: 'https://other.example.test', userId: 'a' })
  await mount('report')
  expect(platform.request).not.toHaveBeenCalled()
  expect(text()).not.toContain('配对平均能耗')
  expect(platform.write).not.toHaveBeenCalled()
})
it('tariff changes survive reopening only for the exact origin/account and allow zero', async () => {
  await mount('tariff')
  for (let index = 0; index < 4; index++) await act(async () => { renderer!.root.findAllByType('input')[index].props.onInput({ detail: { value: ['USD', '0', '.7', '.3'][index] } }) })
  await click('保存本机电价')
  await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null
  await mount('tariff')
  expect(renderer!.root.findAllByType('input').slice(0, 4).map(node => node.props.value)).toEqual(['USD', '0', '0.7', '0.3'])
  expect(platform.request).not.toHaveBeenCalled()
})
it('an account switch cannot save the previous account tariff draft into the new account', async () => {
  await mount('tariff')
  for (let index = 0; index < 4; index++) await act(async () => { renderer!.root.findAllByType('input')[index].props.onInput({ detail: { value: ['USD', '0', '.7', '.3'][index] } }) })
  platform.values.set('matelink.wechat.session.v1', { apiOrigin: 'https://api.example.test', accessToken: 'new', userId: 'b' })
  await click('保存本机电价')
  expect([...platform.values.keys()].some(key => key.startsWith('matelink.tariff.'))).toBe(false)
  expect(text()).toContain('账号已切换')
})
it('a selected historical instant reads authorized route samples and never invents an interpolated location', async () => {
  await mount('whereWasI', { timestamp: '2026-01-01T00:10:00Z' })
  await click('查询这个时刻')
  expect(text()).toContain('最近已有的行程位置采样（非插值）')
  expect(renderer!.root.findByType('map' as React.ElementType).props.latitude).toBeCloseTo(39.901403529849404, 8)
  expect(JSON.stringify([...platform.values.values()])).not.toContain('116.4')
})
