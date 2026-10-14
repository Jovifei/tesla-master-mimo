import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>(), toast: vi.fn(), back: vi.fn(),
  load: ((_options: Record<string, string>) => {}) as (options: Record<string, string>) => void,
  hide: (() => {}) as () => void, show: (() => {}) as () => void, unload: (() => {}) as () => void }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', WebView: 'webview', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => platform.values.set(key, value),
  removeStorageSync: (key: string) => platform.values.delete(key),
  showToast: platform.toast, navigateBack: platform.back,
}, useLoad: (fn: (options: Record<string, string>) => void) => { platform.load = fn },
useDidHide: (fn: () => void) => { platform.hide = fn }, useDidShow: (fn: () => void) => { platform.show = fn },
useUnload: (fn: () => void) => { platform.unload = fn } }))
const pendingKey = 'matelink.wechat.tesla-auth.v1'
const pending = (transactionId: string) => ({ transactionId, clientProof: `fixture-proof-${transactionId}`, apiOrigin: 'https://api.example.test', expiresAt: null })
const respond = (data: unknown) => ({ statusCode: 200, data, header: {} })
let renderer: ReactTestRenderer | null = null
beforeEach(() => {
  vi.resetModules(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  platform.values.clear(); platform.request.mockReset(); platform.toast.mockReset(); platform.back.mockReset()
  platform.hide = () => {}; platform.show = () => {}
  platform.values.set(pendingKey, pending('a'))
})
afterEach(async () => {
  if (renderer) { await act(async () => { platform.unload(); renderer!.unmount() }); renderer = null }
  vi.unstubAllEnvs()
})
async function flush() { await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) }) }
async function mount(options: Record<string, string> = {}) {
  const Page = (await import('./index')).default
  await act(async () => { renderer = TestRenderer.create(React.createElement(Page)) })
  await act(async () => { platform.load(options) }); await flush()
  return renderer!
}
const visible = () => JSON.stringify(renderer!.toJSON())
async function checkStatus() {
  const button = renderer!.root.findAllByType('button').find(node => node.children.join('') === '检查授权状态')!
  await act(async () => { button.props.onClick() }); await flush()
}

/** Real AuthPage and session/API layers; replace only platform transport, lifecycle and WebView. */
it('WebView error payload never appears in rendered authorization error', async () => {
  await mount({ url: encodeURIComponent('https://auth.tesla.com/oauth2/v3/authorize?state=fixture') })
  await act(async () => { renderer!.root.findByType('webview' as React.ElementType).props.onMessage({ detail: { data: [{ error: 'SECRET_ACCESS_TOKEN' }] } }) })
  expect(visible()).not.toContain('SECRET_ACCESS_TOKEN')
  expect(visible()).toContain('Tesla 授权失败')
})

it('unknown backend status is mapped to safe generic text', async () => {
  platform.request.mockResolvedValueOnce(respond({ status: 'SECRET_BACKEND_DIAGNOSTIC' }))
  await mount(); await checkStatus()
  expect(visible()).not.toContain('SECRET_BACKEND_DIAGNOSTIC')
})

it('late expired status cannot clear a newer authorization transaction', async () => {
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await mount(); await checkStatus()
  platform.values.set(pendingKey, pending('b'))
  await act(async () => { release(respond({ status: 'expired' })) }); await flush()
  expect(platform.values.get(pendingKey)).toEqual(pending('b'))
  expect(visible()).not.toContain('expired')
})

it('hidden page does not claim a late ready status or publish success', async () => {
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  await mount(); await checkStatus()
  await act(async () => { platform.hide() })
  platform.request.mockResolvedValue(respond({ access_token: 'fixture-access', user: { id: 'fixture-user' } }))
  await act(async () => { release(respond({ status: 'ready' })) }); await flush()
  expect(platform.request).toHaveBeenCalledTimes(1)
  expect(platform.toast).not.toHaveBeenCalled()
  expect(platform.back).not.toHaveBeenCalled()
  expect(platform.values.has('matelink.wechat.session.v1')).toBe(false)
})

it('router callback preserves actual transaction proof and authenticated claim flow', async () => {
  platform.request.mockResolvedValueOnce(respond({ access_token: 'fixture-access', refresh_token: 'fixture-refresh', user: { id: 'fixture-user' } }))
  await mount({ callback_ref: 'fixture-callback' })
  expect(platform.request.mock.calls[0][0].data).toEqual({ transaction_id: 'a', client_proof: 'fixture-proof-a', callback_ref: 'fixture-callback' })
  expect(platform.values.get('matelink.wechat.session.v1')).toMatchObject({ userId: 'fixture-user', accessToken: 'fixture-access' })
  expect(platform.back).toHaveBeenCalledTimes(1)
})

it('untrusted router URL never mounts a WebView', async () => {
  await mount({ url: encodeURIComponent('https://untrusted.example/authorize') })
  expect(renderer!.root.findAllByType('webview' as React.ElementType)).toHaveLength(0)
  expect(platform.request).not.toHaveBeenCalled()
})

it('platform network Error is never rendered as private authorization detail', async () => {
  platform.request.mockRejectedValueOnce(new Error('SECRET_TRANSPORT_URL'))
  await mount(); await checkStatus()
  expect(visible()).not.toContain('SECRET_TRANSPORT_URL')
  expect(visible()).toContain('网络暂时不可用')
})

it('hidden WebView callback cannot initiate a transaction claim', async () => {
  await mount({ url: encodeURIComponent('https://auth.tesla.com/oauth2/v3/authorize?state=fixture') })
  await act(async () => { platform.hide() })
  await act(async () => { renderer!.root.findByType('webview' as React.ElementType).props.onMessage({ detail: { data: [{ callback_ref: 'fixture-late' }] } }) })
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.toast).not.toHaveBeenCalled()
})

it('reuses an actual pending claim promise and still accepts its owned session', async () => {
  const { matelinkApi } = await import('../../services/api')
  let release!: (value: unknown) => void
  platform.request.mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
  const existingClaim = matelinkApi.claimWechatAuthorization('fixture-callback')
  await mount({ callback_ref: 'fixture-callback' })
  expect(platform.request).toHaveBeenCalledTimes(1)
  await act(async () => {
    release(respond({ access_token: 'fixture-owned-access', user: { id: 'fixture-owner' } }))
    await existingClaim
  })
  await flush()
  expect(platform.request).toHaveBeenCalledTimes(1)
  expect(platform.values.get('matelink.wechat.session.v1')).toMatchObject({ userId: 'fixture-owner', accessToken: 'fixture-owned-access' })
  expect(platform.toast).toHaveBeenCalledTimes(1)
  expect(platform.back).toHaveBeenCalledTimes(1)
})
