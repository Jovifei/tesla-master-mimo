import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), toast: vi.fn(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Checkbox: 'checkbox', CheckboxGroup: 'checkboxgroup' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => platform.values.set(key, value),
  removeStorageSync: (key: string) => platform.values.delete(key), showToast: platform.toast,
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn },
useUnload: (fn: () => void) => { platform.unload = fn } }))
const key = 'matelink.wechat.session.v1'
const session = (userId: string) => ({ accessToken: `fixture-${userId}`, refreshToken: null, expiresAt: null, userId, linkRequired: false })
const respond = (statusCode: number, data: unknown) => ({ statusCode, data, header: {} })
let renderer: ReactTestRenderer | null = null
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.request.mockReset(); platform.toast.mockReset()
  platform.values.set(key, session('a'))
})
afterEach(async () => {
  if (renderer) { await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null }
  vi.unstubAllEnvs()
})
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount() {
  const Page = (await import('./index')).default
  await act(async () => { renderer = TestRenderer.create(React.createElement(Page)); })
  await act(async () => { platform.show() }); await flush()
}
async function logout() {
  const button = renderer!.root.findAllByType('button').find(node => node.children.join('') === '退出登录')!
  await act(async () => { button.props.onClick() }); await flush()
}
const visible = () => JSON.stringify(renderer!.toJSON())

/** Actual page -> actual API logout -> platform transport/storage, no API mocking. */
it('successful logout exposes login and clears the actual local session', async () => {
  platform.request.mockResolvedValueOnce(respond(200, { status: 'logged_out' }))
  await mount(); await logout()
  expect(platform.values.has(key)).toBe(false)
  expect(visible()).toContain('尚未登录')
  expect(renderer!.root.findAllByType('button').some(node => node.children.join('') === '微信登录')).toBe(true)
})

it.each(['network', 'http500'])('failed %s logout still synchronizes already-cleared local session', async failure => {
  if (failure === 'network') platform.request.mockRejectedValueOnce(Error('PRIVATE_TRANSPORT_DIAGNOSTIC'))
  else platform.request.mockResolvedValueOnce(respond(500, { message: 'PRIVATE_SERVER_DIAGNOSTIC' }))
  await mount(); await logout()
  expect(platform.values.has(key)).toBe(false)
  expect(visible()).toContain('尚未登录')
  expect(visible()).not.toContain('PRIVATE_')
  expect(renderer!.root.findAllByType('button').some(node => node.children.join('') === '微信登录')).toBe(true)
  expect(platform.toast).not.toHaveBeenCalled()
})

it('late successful logout synchronizes a newer local session instead of clearing its UI', async () => {
  const { matelinkApi } = await import('../../services/api')
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await mount(); await logout()
  platform.request.mockResolvedValueOnce(respond(200, { access_token: 'fixture-b', user: { id: 'b' }, link_required: false }))
  await act(async () => { await matelinkApi.exchangeTeslaTicket('fixture-ticket') })
  await act(async () => { release(respond(200, { status: 'logged_out' })) }); await flush()
  expect(platform.values.get(key)).toEqual(session('b'))
  expect(visible()).toContain('会话已建立')
  expect(renderer!.root.findAllByType('button').some(node => node.children.join('') === '微信登录')).toBe(false)
  expect(platform.toast).not.toHaveBeenCalled()
})

it('hidden lifecycle invalidates a late logout completion and re-show reads the new session', async () => {
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await mount(); await logout()
  await act(async () => { platform.hide() })
  platform.values.set(key, session('b'))
  await act(async () => { release(respond(200, { status: 'logged_out' })) }); await flush()
  expect(platform.toast).not.toHaveBeenCalled()
  await act(async () => { platform.show() }); await flush()
  expect(platform.values.get(key)).toEqual(session('b'))
  expect(visible()).toContain('会话已建立')
})
